package dev.vsdeadshot.flashcards.service;

import dev.vsdeadshot.flashcards.domain.AuthToken;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues bearer tokens and answers who is presenting one.
 *
 * <p>Tokens are opaque and looked up, rather than signed and self-describing. A JWT would save
 * this query, and at one user on one device that saving buys nothing while giving up the thing
 * that actually matters here: a token this service issued can be withdrawn, immediately, by
 * writing to the row it came from. A lost phone is then a database update rather than a key
 * rotation and a rebuild.
 */
@Service
public class TokenService {

    /**
     * One hour, and deliberately not the fifteen minutes that is the reflex.
     *
     * <p>This client syncs from WorkManager in the background, so every expiry is a refresh
     * request and a radio wake that the user is not present for. Against a threat model of one
     * personal application, no third-party clients, and revocation that works, an hour is the
     * better trade. The refresh token is what keeps the window that follows it short.
     */
    public static final Duration ACCESS_TOKEN_TTL = Duration.ofHours(1);

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
     * A freshly issued token and how long the holder may use it.
     *
     * <p>The raw token appears here and nowhere else — not in the entity, not in the database,
     * and not in any log line. This return value is the only time it exists on this side.
     */
    public record Issued(String token, long expiresInSeconds) {
    }

    @Transactional
    public Issued issue(String userId) {
        byte[] material = new byte[TOKEN_BYTES];
        random.nextBytes(material);
        // URL-safe and unpadded, so the value survives a header, a query string and a
        // properties file without anything having to escape it.
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(material);

        Instant now = clock.instant();
        Instant expiresAt = now.plus(ACCESS_TOKEN_TTL);
        tokens.save(new AuthToken(userId, digest(token), now, expiresAt));

        return new Issued(token, ACCESS_TOKEN_TTL.toSeconds());
    }

    /**
     * The owner this token authenticates, or empty if it authenticates nobody.
     *
     * <p>One answer for every way a token can fail — unknown, expired, revoked, malformed. A
     * caller that could tell them apart would eventually report the difference to whoever
     * presented it, and "this token existed but has expired" is more than a rejected caller
     * needs to know.
     */
    @Transactional(readOnly = true)
    public Optional<String> authenticate(String presented) {
        if (presented == null || presented.isBlank()) {
            return Optional.empty();
        }
        return tokens.findByTokenHash(digest(presented))
                .filter(token -> token.isUsableAt(clock.instant()))
                .map(AuthToken::getUserId);
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
            return HexFormat.of().formatHex(sha256.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // Every JVM is required to provide SHA-256, so this is unreachable rather than
            // unhandled -- and if it ever were reached, continuing would mean storing tokens
            // in some other form entirely.
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }
}
