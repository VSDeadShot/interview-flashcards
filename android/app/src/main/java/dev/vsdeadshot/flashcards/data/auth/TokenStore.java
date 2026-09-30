package dev.vsdeadshot.flashcards.data.auth;

import androidx.annotation.Nullable;

/**
 * Where the tokens live, and who is allowed to ask.
 *
 * <p>An interface over one production implementation, which is worth the indirection for a
 * reason particular to this project: {@code PrefsTokenStore} needs a {@code Context} and so
 * needs Robolectric, and the remote and sync tests deliberately do not run under it. Without a
 * seam here, adding authentication to the client would have dragged the Android framework into
 * every test that builds an API client — the opposite of the direction those tests were written
 * in.
 *
 * <p><strong>No expiry is stored.</strong> The server sends {@code expiresIn} as a duration
 * rather than an instant precisely because this device's clock may be wrong, and a client that
 * turned it back into a timestamp would have reintroduced the assumption the server declined to
 * make. Nothing here needs one: an access token is refreshed when a request comes back
 * {@code 401}, which is a fact rather than a prediction.
 */
public interface TokenStore {

    /**
     * Why there is no token, which is not the same question as whether there is one.
     *
     * <p>The three are told apart because the screen says something different about each. A
     * session that died under somebody is the case that matters — their outbox is now stalled
     * and nothing else in the app will say so.
     */
    enum SignedOutReason {

        /** No one has signed in on this device yet. */
        NEVER,

        /**
         * There were tokens and the server stopped accepting them — thirty days elapsed, a
         * logout elsewhere, or reuse detection revoking the family. All three are one event to
         * a client: sign in again.
         */
        SESSION_EXPIRED,

        /** Somebody signed out on purpose. */
        SIGNED_OUT
    }

    /** @param reason meaningful only when {@code signedIn} is false. */
    record AuthState(boolean signedIn, SignedOutReason reason) {
    }

    /** Notified on the thread that made the change, which is not necessarily the main thread. */
    interface Listener {
        void onAuthChanged(AuthState state);
    }

    @Nullable
    String accessToken();

    @Nullable
    String refreshToken();

    /**
     * Stores a freshly issued pair.
     *
     * <p>Both together, never one: the server rotates the refresh token on every exchange, so
     * saving a new access token beside a spent refresh token would leave this device holding a
     * value the server treats as evidence of a copy — and presenting it would revoke the whole
     * family.
     */
    void save(String accessToken, String refreshToken);

    void signOut(SignedOutReason reason);

    /**
     * Stores a renewed pair, but only if {@code spentRefreshToken} — the one exchanged for it —
     * is still the refresh token held here.
     *
     * <p>A renewal waits on the network, and the session it belongs to can end while it does: a
     * sign-out, or a sign-out followed by a fresh sign-in. Writing the pair regardless would undo
     * that. If the logout reached the server the pair is already revoked and the device would
     * show a session that is not there; if the logout failed on the network, it would be a
     * working session somebody had just asked to end. The check and the write are one atomic
     * step against every other write here.
     *
     * @return whether the pair was stored
     */
    boolean saveIfCurrent(String spentRefreshToken, String accessToken, String refreshToken);

    /**
     * Ends the session for {@code reason}, but only if {@code spentRefreshToken} is still the
     * refresh token held here. A refusal that arrives after somebody signed out is about a token
     * the device no longer holds, and recording it would relabel their sign-out as an expiry.
     *
     * @return whether the session was ended
     */
    boolean signOutIfCurrent(String spentRefreshToken, SignedOutReason reason);

    AuthState state();

    void addListener(Listener listener);

    void removeListener(Listener listener);
}
