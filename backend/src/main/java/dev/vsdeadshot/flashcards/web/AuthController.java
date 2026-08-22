package dev.vsdeadshot.flashcards.web;

import dev.vsdeadshot.flashcards.config.FlashcardsProperties;
import dev.vsdeadshot.flashcards.service.AuthenticationFailedException;
import dev.vsdeadshot.flashcards.service.PassphraseAuthenticator;
import dev.vsdeadshot.flashcards.service.SignInNotConfiguredException;
import dev.vsdeadshot.flashcards.service.TokenService;
import dev.vsdeadshot.flashcards.web.dto.LoginRequest;
import dev.vsdeadshot.flashcards.web.dto.RefreshTokenRequest;
import dev.vsdeadshot.flashcards.web.dto.TokenResponse;
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
    private final FlashcardsProperties properties;

    public AuthController(PassphraseAuthenticator passphrases, TokenService tokens,
            FlashcardsProperties properties) {
        this.passphrases = passphrases;
        this.tokens = tokens;
        this.properties = properties;
    }

    /**
     * The owner is read from configuration rather than from the request, because there is one
     * and a client does not get to name it. When this stops being one user, what changes is
     * that the passphrase resolves to a subject -- and the token already carries whatever it
     * resolved to, so nothing downstream of here moves.
     */
    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        if (!passphrases.configured()) {
            throw new SignInNotConfiguredException();
        }
        if (!passphrases.matches(request.passphrase())) {
            throw new AuthenticationFailedException();
        }
        return respond(tokens.issue(properties.userId()));
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
