package dev.vsdeadshot.flashcards.data.auth;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The tokens, in a {@link SharedPreferences} file of their own.
 *
 * <p><strong>Not {@code EncryptedSharedPreferences}, and that is a decision rather than an
 * omission.</strong> {@code androidx.security:security-crypto} is deprecated — its last release
 * is an alpha, and the replacement Google points at is DataStore with Tink, which is
 * Coroutines-first and so is ruled out by this project's Java-only constraint. That left a
 * choice between an abandoned library and being clear about what the protection actually is.
 *
 * <p>What actually protects these values is the application sandbox: another app cannot read
 * this file, and one that could — on a rooted device, or with physical access and an unlocked
 * bootloader — could equally read the key material an encrypted store would have to keep on the
 * same device. The real hole was never the file, it was <em>backup</em>: a token copied off the
 * device into a cloud archive leaves the sandbox entirely. That is closed in the manifest,
 * with {@code allowBackup="false"} and an extraction rule that names this file, rather than
 * here.
 *
 * <p>Held as one instance per process, the same way {@code FlashcardsDatabase} is, so a listener
 * registered by a screen sees a write made by the sync.
 */
public final class PrefsTokenStore implements TokenStore {

    /**
     * Its own file rather than the default one. It is what an extraction rule has to name to
     * exclude, and a rule excluding every preference this app might ever keep would be a rule
     * nobody could safely narrow later.
     */
    @VisibleForTesting
    static final String FILE = "auth";

    private static final String KEY_ACCESS = "accessToken";
    private static final String KEY_REFRESH = "refreshToken";
    private static final String KEY_REASON = "signedOutReason";

    private static volatile PrefsTokenStore instance;

    private final SharedPreferences prefs;

    /**
     * Kept in a map of our own because {@link SharedPreferences} holds its listeners weakly. A
     * lambda registered and forgotten would be collected at an unpredictable moment and the
     * screen would simply stop updating — the kind of failure that looks like a race.
     */
    private final Map<Listener, SharedPreferences.OnSharedPreferenceChangeListener> listeners =
            new ConcurrentHashMap<>();

    private PrefsTokenStore(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static PrefsTokenStore get(Context context) {
        PrefsTokenStore existing = instance;
        if (existing != null) {
            return existing;
        }
        synchronized (PrefsTokenStore.class) {
            if (instance == null) {
                instance = new PrefsTokenStore(context);
            }
            return instance;
        }
    }

    @Nullable
    @Override
    public String accessToken() {
        return prefs.getString(KEY_ACCESS, null);
    }

    @Nullable
    @Override
    public String refreshToken() {
        return prefs.getString(KEY_REFRESH, null);
    }

    /**
     * {@code commit()} rather than {@code apply()}, which is the one place in this class the
     * distinction matters. This is called from the middle of an OkHttp authenticator, and the
     * request it is about to retry carries the token being written; an asynchronous write could
     * still be in flight when the process is killed, leaving the device holding a refresh token
     * the server has already spent — which the next attempt to use would be read as reuse and
     * would revoke the family.
     */
    @SuppressLint("ApplySharedPref")
    @Override
    public void save(String accessToken, String refreshToken) {
        prefs.edit()
                .putString(KEY_ACCESS, accessToken)
                .putString(KEY_REFRESH, refreshToken)
                .remove(KEY_REASON)
                .commit();
    }

    /**
     * {@code commit()} for {@link #save}'s reason inverted: a sign-out still in flight when the
     * process dies leaves a token on the device that nothing intends to use again.
     */
    @SuppressLint("ApplySharedPref")
    @Override
    public void signOut(SignedOutReason reason) {
        prefs.edit()
                .remove(KEY_ACCESS)
                .remove(KEY_REFRESH)
                .putString(KEY_REASON, reason.name())
                .commit();
    }

    @Override
    public AuthState state() {
        String access = accessToken();
        if (access != null && refreshToken() != null) {
            return new AuthState(true, SignedOutReason.NEVER);
        }
        return new AuthState(false, reason());
    }

    private SignedOutReason reason() {
        String stored = prefs.getString(KEY_REASON, null);
        if (stored == null) {
            return SignedOutReason.NEVER;
        }
        try {
            return SignedOutReason.valueOf(stored);
        } catch (IllegalArgumentException e) {
            // A value written by a version of this app that named its reasons differently. The
            // tokens are gone either way, which is the part the caller acts on.
            return SignedOutReason.NEVER;
        }
    }

    @Override
    public void addListener(@NonNull Listener listener) {
        SharedPreferences.OnSharedPreferenceChangeListener delegate =
                (changed, key) -> listener.onAuthChanged(state());
        listeners.put(listener, delegate);
        prefs.registerOnSharedPreferenceChangeListener(delegate);
    }

    @Override
    public void removeListener(@NonNull Listener listener) {
        SharedPreferences.OnSharedPreferenceChangeListener delegate = listeners.remove(listener);
        if (delegate != null) {
            prefs.unregisterOnSharedPreferenceChangeListener(delegate);
        }
    }

    /**
     * Drops the process-wide instance so the next {@link #get} builds one against whatever
     * context the caller supplies. Production never calls this; a test that has finished with a
     * Robolectric application does, since the preferences file outlives it otherwise.
     */
    @VisibleForTesting
    public static void reset() {
        instance = null;
    }
}
