package dev.vsdeadshot.flashcards.domain;

/**
 * What an issued token is for.
 *
 * <p>The distinction is enforced rather than conventional: an access token presented at
 * {@code /auth/refresh} is refused, and a refresh token presented as a bearer credential is
 * refused. Without that, the long-lived token would double as an hour-long one and its length
 * would stop meaning anything.
 */
public enum TokenKind {

    /** Presented on every request. Short-lived, so a copy of one stops working on its own. */
    ACCESS,

    /**
     * Presented only to obtain the next access token, and replaced every time it is. Long-lived,
     * which is affordable precisely because using it invalidates it.
     */
    REFRESH
}
