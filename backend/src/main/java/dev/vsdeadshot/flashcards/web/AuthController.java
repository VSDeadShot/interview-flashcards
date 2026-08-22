package dev.vsdeadshot.flashcards.web;

import dev.vsdeadshot.flashcards.config.FlashcardsProperties;
import dev.vsdeadshot.flashcards.service.AuthenticationFailedException;
import dev.vsdeadshot.flashcards.service.LoginRateLimit;
import dev.vsdeadshot.flashcards.service.PassphraseAuthenticator;
import dev.vsdeadshot.flashcards.service.SignInNotConfiguredException;
import dev.vsdeadshot.flashcards.service.TokenService;
import dev.vsdeadshot.flashcards.web.dto.LoginRequest;
import dev.vsdeadshot.flashcards.web.dto.RefreshTokenRequest;
import dev.vsdeadshot.flashcards.web.dto.TokenResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /auth}. How a client turns a passphrase into a token.
 *
 * <p>Reachable without authenticating, which {@link PublicRoutes} is what decides -- requiring a
 * credential to obtain one is a closed loop. That makes this the only route in the application
 * an unauthenticated caller can put a body into, and the reason the sign-in rate limit is not
 * an optional extra.
 *
 * <p>Thin like the others: no branching on why something failed, because the outcomes are
 * exceptions and {@link ApiExceptionHandler} owns what each becomes on the wire.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final PassphraseAuthenticator passphrases;
    private final TokenService tokens;
    private final LoginRateLimit rateLimit;
    private final FlashcardsProperties properties;

    public AuthController(PassphraseAuthenticator passphrases, TokenService tokens,
            LoginRateLimit rateLimit, FlashcardsProperties properties) {
        this.passphrases = passphrases;
        this.tokens = tokens;
        this.rateLimit = rateLimit;
        this.properties = properties;
    }

    /**
     * The owner is read from configuration rather than from the request, because there is one
     * and a client does not get to name it. When this stops being one user, what changes is
     * that the passphrase resolves to a subject -- and the token already carries whatever it
     * resolved to, so nothing downstream of here moves.
     */
    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request,
            HttpServletRequest http) {
        if (!passphrases.configured()) {
            // Ahead of the limit deliberately. Nothing is hashed and no credential is consulted
            // when sign-in is switched off, so there is no cost to bound and no attempt to count.
            throw new SignInNotConfiguredException();
        }

        String source = clientAddress(http);
        // Before the passphrase is checked, not after. Bcrypt is expensive on purpose, so a
        // limit applied afterwards has already paid for the request it was meant to refuse --
        // the same ordering GenerationQuota keeps in front of the model call.
        rateLimit.check(source);

        if (!passphrases.matches(request.passphrase())) {
            rateLimit.recordFailure(source);
            throw new AuthenticationFailedException();
        }
        return respond(tokens.issue(properties.userId()));
    }

    /**
     * Where the attempt appears to come from.
     *
     * <p>Reads {@code getRemoteAddr()} rather than a header, which is what makes this correct in
     * both deployments: behind a proxy {@code server.forward-headers-strategy} has Spring rewrite
     * it from {@code X-Forwarded-For}, and locally there is no proxy and it is the peer address.
     * The decision about whether a forwarded header can be trusted therefore lives in
     * configuration, next to the bind address it depends on, rather than being re-made here.
     *
     * <p>Truncated to the column's width. An address longer than an IPv6 literal did not come
     * from a proxy this application should be believing anyway, and a value that cannot be
     * stored would fail the write that records the failure.
     */
    private static String clientAddress(HttpServletRequest http) {
        String address = http.getRemoteAddr();
        if (address == null || address.isBlank()) {
            // Attributable to nothing, so it counts only against the global limit -- which is
            // the one that cannot be evaded, and exactly the right place for it to land.
            return "unknown";
        }
        return address.length() > 64 ? address.substring(0, 64) : address;
    }

    /**
     * Exchanges a refresh token for the next pair. The presented one stops working in the same
     * transaction that issues its successor.
     *
     * <p>Answers {@code 401} for every way this can fail -- unknown, expired, revoked, the wrong
     * kind of token, or already exchanged. A client's response to all of them is the same: sign
     * in again. Distinguishing "already exchanged" in the response would tell whoever presented
     * a copied token exactly what had been noticed about it.
     */
    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return respond(tokens.refresh(request.refreshToken()));
    }

    /**
     * Ends the session this refresh token belongs to -- that chain only, not every token the
     * user holds, so signing out on one device leaves another alone.
     *
     * <p>{@code 204} whether or not the token was recognised. Reporting an unknown token would
     * tell somebody probing whether a value they hold is real, and a client signing out has
     * nothing to do differently either way.
     */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshTokenRequest request) {
        tokens.logout(request.refreshToken());
    }

    private static TokenResponse respond(TokenService.Issued issued) {
        return new TokenResponse(issued.accessToken(), issued.expiresInSeconds(),
                issued.refreshToken(), issued.refreshExpiresInSeconds());
    }
}
