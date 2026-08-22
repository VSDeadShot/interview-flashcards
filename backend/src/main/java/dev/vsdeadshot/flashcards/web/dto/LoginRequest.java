package dev.vsdeadshot.flashcards.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * What a client sends to obtain a token.
 *
 * <p>{@code @NotBlank} earns its place for the reason the other validation here does: without
 * it a blank passphrase would reach the encoder, and the honest answer to "you sent nothing" is
 * {@code 400} rather than the {@code 401} that says a real attempt was wrong.
 *
 * <p>There is no {@code @Size} bound. Bcrypt truncates past 72 bytes, which is a property of
 * the algorithm rather than a limit to police, and refusing a long passphrase would be refusing
 * the strong end of the range.
 */
public record LoginRequest(@NotBlank String passphrase) {
}
