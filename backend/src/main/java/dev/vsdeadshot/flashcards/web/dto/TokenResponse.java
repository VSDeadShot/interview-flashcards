package dev.vsdeadshot.flashcards.web.dto;

/**
 * A newly issued token and its lifetime.
 *
 * <p>{@code expiresIn} is seconds of remaining life rather than an absolute instant, and that
 * is deliberate: a client comparing an expiry timestamp against its own clock is comparing
 * against a clock this server does not control. A duration is correct on a device whose time is
 * wrong, which -- given this application already accepts reviews from such devices -- is not
 * hypothetical.
 */
public record TokenResponse(String accessToken, long expiresIn) {
}
