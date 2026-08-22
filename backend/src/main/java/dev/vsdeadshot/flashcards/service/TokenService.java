package dev.vsdeadshot.flashcards.service;

import dev.vsdeadshot.flashcards.domain.AuthToken;
import dev.vsdeadshot.flashcards.domain.TokenKind;
import dev.vsdeadshot.flashcards.repository.AuthTokenRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues bearer tokens, rotates them, and answers who is presenting one.
 *
 * <p>Tokens are opaque and looked up, rather than signed and self-describing. A JWT would save
 * this query, and at one user on one device that saving buys nothing while giving up the thing
 * that actually matters here: a token this service issued can be withdrawn, immediately, by
 * writing to the row it came from. A lost phone is then a database update rather than a key
 * rotation and a rebuild. Reuse detection needs that same property — there is nowhere to record
 * that a stateless token was already spent.
 */
@Service
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);

    /**
     * One hour, and deliberately not the fifteen minutes that is the reflex.
     *
     * <p>This client syncs from WorkManager in the background, so every expiry is a refresh
     * request and a radio wake that the user is not present for. Against a threat model of one
     * personal application, no third-party clients, and revocation that works, an hour is the
     * better trade.
     */
    public static final Duration ACCESS_TOKEN_TTL = Duration.ofHours(1);

    /**
     * Thirty days, which is affordable only because using a refresh token replaces it. A
     * long-lived credential that stayed valid after use would be the API key again with extra
     * steps.
     *
     * <p>The consequence worth knowing: a device untouched for longer than this has to sign in
     * again, and until it does its outbox goes nowhere. That is correct, and it is only
     * acceptable because the client surfaces it rather than syncing silently into nothing.
     */
    public static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(30);

    /**
     * 256 bits. Far past guessing, which is the property that lets the stored digest be a plain
     * SHA-256 rather than a password hash.
     */
    private static final int TOKEN_BYTES = 32;

    private final AuthTokenRepository tokens;
    private final Clock clock;

    /** Seeded by the platform. Never {@code Random}, whose output is predictable from any of it. */
    private final SecureRandom random = new SecureRandom();

    public TokenService(AuthTokenRepository tokens, Clock clock) {
        this.tokens = tokens;
        this.clock = clock;
    }

    /**
     * A freshly issued pair and how long each half lasts.
     *
     * <p>The raw tokens appear here and nowhere else — not in the entities, not in the database,
     * and not in any log line. This return value is the only time they exist on this side.
     */
    public record Issued(String accessToken, long expiresInSeconds,
                         String refreshToken, long refreshExpiresInSeconds) {
    }

    /** A new sign-in: a new family, and the first pair in it. */
    @Transactional
    public Issued issue(String userId) {
        return issueInFamily(userId, UUID.randomUUID());
    }

    /**
     * Exchanges a refresh token for the next pair.
     *
     * <p>The presented token is rotated in the same transaction that issues its successor, so
     * there is no window in which both are usable.
     *
     * <p><strong>{@code noRollbackFor} is load-bearing, not tidiness.</strong> Detecting reuse
     * writes the family's revocation and then refuses the request — and a {@code RuntimeException}
     * rolls a transaction back by default, which would undo exactly the revocation that is the
     * entire response to a stolen token. The refusal would still reach the caller, so nothing
     * would look wrong, and the copied token would keep working for another thirty days.
     * {@code reuseRevokesTheFamily} is what caught it.
     *
     * <p>Safe to apply to the whole method: the other paths that throw this have written nothing,
     * so there is no partial work for a commit to preserve.
     *
     * @throws AuthenticationFailedException if the token is unknown, expired, revoked, of the
     *         wrong kind, or has already been exchanged
     */
    @Transactional(noRollbackFor = AuthenticationFailedException.class)
    public Issued refresh(String presented) {
        Instant now = clock.instant();
        AuthToken token = tokens.findByTokenHash(digest(presented))
                .orElseThrow(AuthenticationFailedException::new);

        if (token.getKind() != TokenKind.REFRESH) {
            // An access token would otherwise buy a fresh thirty-day window every hour, which
            // would make its own one-hour life meaningless.
            throw new AuthenticationFailedException();
        }

        if (token.isRotated()) {
            // Two parties hold this token: the client that legitimately exchanged it, and
            // whoever copied it. There is no way to tell which one is presenting it now, so the
            // only safe answer is to trust neither -- the whole family goes, and both are sent
            // back to the passphrase. Signing in again is a far cheaper failure than leaving a
            // copied token working.
            log.warn("Refresh token reuse detected for family {}; revoking the family",
                    token.getFamilyId());
            revokeFamily(token.getFamilyId(), now);
            throw new AuthenticationFailedException();
        }

        if (!token.isUsableAt(now)) {
            throw new AuthenticationFailedException();
        }

        token.rotate(now);
        tokens.save(token);
        return issueInFamily(token.getUserId(), token.getFamilyId());
    }

    /**
     * Signs out the chain this refresh token belongs to, and only that chain.
     *
     * <p><strong>Family-scoped rather than user-scoped on purpose.</strong> Signing out on one
     * device has no business ending a session on another, and while there is one device the two
     * behave identically — so the narrower rule costs nothing now and is already correct when a
     * second device appears.
     *
     * <p>Silent about a token it does not recognise. Sign-out is the one operation where saying
     * "no such token" would tell somebody probing whether a value they hold is real, and there
     * is nothing a caller could usefully do with the answer either way.
     */
    @Transactional
    public void logout(String presented) {
        tokens.findByTokenHash(digest(presented))
                .ifPresent(token -> revokeFamily(token.getFamilyId(), clock.instant()));
    }

    /**
     * The owner this token authenticates, or empty if it authenticates nobody.
     *
     * <p>One answer for every way a token can fail — unknown, expired, revoked, rotated, or the
     * wrong kind. A caller that could tell them apart would eventually report the difference to
     * whoever presented it, and "this token existed but has expired" is more than a rejected
     * caller needs to know.
     */
    @Transactional(readOnly = true)
    public Optional<String> authenticate(String presented) {
        if (presented == null || presented.isBlank()) {
            return Optional.empty();
        }
        return tokens.findByTokenHash(digest(presented))
                // A refresh token is not a credential for the API. It is deliberately long-lived
                // and is only ever sent to one endpoint; accepting it here would give it the
                // reach of an access token with thirty days of life behind it.
                .filter(token -> token.getKind() == TokenKind.ACCESS)
                .filter(token -> token.isUsableAt(clock.instant()))
                .map(AuthToken::getUserId);
    }

    private Issued issueInFamily(String userId, UUID familyId) {
        Instant now = clock.instant();
        String accessToken = mint();
        String refreshToken = mint();

        tokens.save(new AuthToken(userId, digest(accessToken), TokenKind.ACCESS, familyId,
                now, now.plus(ACCESS_TOKEN_TTL)));
        tokens.save(new AuthToken(userId, digest(refreshToken), TokenKind.REFRESH, familyId,
                now, now.plus(REFRESH_TOKEN_TTL)));

        return new Issued(accessToken, ACCESS_TOKEN_TTL.toSeconds(),
                refreshToken, REFRESH_TOKEN_TTL.toSeconds());
    }

    /**
     * Revokes every token in a chain, including the access token a client may be midway through
     * using. That is the point: a family exists so one write ends the whole session rather than
     * leaving an hour of valid access behind the refresh token that was just withdrawn.
     */
    private void revokeFamily(UUID familyId, Instant when) {
        tokens.findByFamilyId(familyId).forEach(token -> {
            token.revoke(when);
            tokens.save(token);
        });
    }

    private String mint() {
        byte[] material = new byte[TOKEN_BYTES];
        random.nextBytes(material);
        // URL-safe and unpadded, so the value survives a header, a query string and a
        // properties file without anything having to escape it.
        return Base64.getUrlEncoder().withoutPadding().encodeToString(material);
    }

    /**
     * SHA-256, hex. Not bcrypt, and the distinction is the whole design: bcrypt exists to make
     * each guess expensive against a secret a human chose, and this secret has 256 bits of
     * entropy behind it. Using it here would charge that cost to every authenticated request to
     * defend against an attack that cannot be mounted.
     */
    private static String digest(String token) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(
                    (token == null ? "" : token).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // Every JVM is required to provide SHA-256, so this is unreachable rather than
            // unhandled -- and if it ever were reached, continuing would mean storing tokens
            // in some other form entirely.
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }
}
