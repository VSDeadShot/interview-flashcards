package dev.vsdeadshot.flashcards.web;

import dev.vsdeadshot.flashcards.config.FlashcardsProperties;
import dev.vsdeadshot.flashcards.service.AuthenticationFailedException;
import dev.vsdeadshot.flashcards.service.PassphraseAuthenticator;
import dev.vsdeadshot.flashcards.service.SignInNotConfiguredException;
import dev.vsdeadshot.flashcards.service.TokenService;
import dev.vsdeadshot.flashcards.web.dto.LoginRequest;
import dev.vsdeadshot.flashcards.web.dto.TokenResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
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
        TokenService.Issued issued = tokens.issue(properties.userId());
        return new TokenResponse(issued.token(), issued.expiresInSeconds());
    }
}
