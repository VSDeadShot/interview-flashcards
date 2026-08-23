package dev.vsdeadshot.flashcards.data.remote.dto;

/**
 * A refresh token being presented — to exchange at {@code /auth/refresh}, or to end the session
 * at {@code /auth/logout}. One class for both, mirroring the server's own single record: it is
 * one field carrying one thing, and two would be two places to keep in step.
 */
public class RefreshRequestDto {

    public final String refreshToken;

    public RefreshRequestDto(String refreshToken) {
        this.refreshToken = refreshToken;
    }
}
