package dev.vsdeadshot.flashcards.data.remote.dto;

/**
 * A newly issued pair, from either {@code /auth/login} or {@code /auth/refresh} — the same shape
 * from both, so a client has one thing to store either way.
 *
 * <p>The lifetimes are read and not kept. They are sent as durations rather than instants
 * because this device's clock is not one the server controls, and turning them into timestamps
 * here would put back exactly the assumption the server declined to make. Nothing needs them:
 * a token is refreshed when a request comes back {@code 401}.
 *
 * <p>Non-final and package-visible fields, like every other DTO here: Moshi writes them
 * reflectively, and a record would need {@code java.lang.Record} reflection that Android's
 * runtime does not provide.
 */
public class TokenResponseDto {

    public String accessToken;
    public long expiresIn;
    public String refreshToken;
    public long refreshExpiresIn;
}
