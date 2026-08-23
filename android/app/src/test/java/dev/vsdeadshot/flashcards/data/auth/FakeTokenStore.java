package dev.vsdeadshot.flashcards.data.auth;

import androidx.annotation.Nullable;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A {@link TokenStore} in memory, for the tests that build an API client without wanting an
 * Android framework underneath them.
 *
 * <p>This is the reason {@code TokenStore} is an interface. The remote and sync tests
 * deliberately do not run under Robolectric — nothing in them touches the framework, and keeping
 * it out is what stops the API-35 pin in {@code robolectric.properties} spreading past the
 * database tests. {@code PrefsTokenStore} needs a {@code Context}, so without a seam, adding a
 * credential to the client would have dragged Robolectric into every one of them.
 */
public final class FakeTokenStore implements TokenStore {

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private String accessToken;
    private String refreshToken;
    private SignedOutReason reason = SignedOutReason.NEVER;

    /** Counts saves, so a test can assert a refresh happened exactly once. */
    private int saves;

    public static FakeTokenStore signedIn(String accessToken, String refreshToken) {
        FakeTokenStore store = new FakeTokenStore();
        store.accessToken = accessToken;
        store.refreshToken = refreshToken;
        return store;
    }

    public static FakeTokenStore signedOut() {
        return new FakeTokenStore();
    }

    /**
     * An access token with no way to renew it.
     *
     * <p>What the tests that build a client and enqueue responses by hand generally want. They
     * are about what the server does with an authenticated request, and a store that could renew
     * would have the authenticator send a {@code /auth/refresh} nobody queued a response for the
     * moment one of them answers {@code 401} — which is a twenty-second read timeout, not a
     * failure with a name. {@code TokenAuthenticatorTest} is where renewal is exercised, and it
     * queues for it.
     */
    public static FakeTokenStore accessOnly(String accessToken) {
        FakeTokenStore store = new FakeTokenStore();
        store.accessToken = accessToken;
        return store;
    }

    @Nullable
    @Override
    public synchronized String accessToken() {
        return accessToken;
    }

    @Nullable
    @Override
    public synchronized String refreshToken() {
        return refreshToken;
    }

    @Override
    public void save(String accessToken, String refreshToken) {
        synchronized (this) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.reason = SignedOutReason.NEVER;
            this.saves++;
        }
        publish();
    }

    @Override
    public void signOut(SignedOutReason reason) {
        synchronized (this) {
            this.accessToken = null;
            this.refreshToken = null;
            this.reason = reason;
        }
        publish();
    }

    @Override
    public synchronized AuthState state() {
        return accessToken != null && refreshToken != null
                ? new AuthState(true, SignedOutReason.NEVER)
                : new AuthState(false, reason);
    }

    @Override
    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public synchronized int saves() {
        return saves;
    }

    private void publish() {
        AuthState current = state();
        for (Listener listener : listeners) {
            listener.onAuthChanged(current);
        }
    }
}
