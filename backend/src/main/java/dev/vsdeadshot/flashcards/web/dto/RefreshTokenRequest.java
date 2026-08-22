package dev.vsdeadshot.flashcards.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * A refresh token being presented -- to exchange at {@code /auth/refresh}, or to sign out with
 * at {@code /auth/logout}.
 *
 * <p>One record for both, because it is one field carrying one thing. Two records differing
 * only in name would be two places to keep a validation rule in step.
 */
public record RefreshTokenRequest(@NotBlank String refreshToken) {
}
