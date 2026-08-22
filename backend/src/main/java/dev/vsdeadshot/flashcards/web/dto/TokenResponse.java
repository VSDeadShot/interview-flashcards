package dev.vsdeadshot.flashcards.web.dto;

/**
 * A newly issued pair of tokens and their lifetimes.
 *
 * <p>The same shape comes back from signing in and from refreshing, so a client has one thing
 * to store either way. The refresh token is new on every response: exchanging one replaces it,
 * so a client that kept the old one would find it rejected -- and would be treated as a copy.
 *
 * <p>{@code expiresIn} is seconds of remaining life rather than an absolute instant, and that
 * is deliberate: a client comparing an expiry timestamp against its own clock is comparing
 * against a clock this server does not control. A duration is correct on a device whose time is
 * wrong, which -- given this application already accepts reviews from such devices -- is not
 * hypothetical.
 */
public record TokenResponse(String accessToken, long expiresIn,
                            String refreshToken, long refreshExpiresIn) {
}
