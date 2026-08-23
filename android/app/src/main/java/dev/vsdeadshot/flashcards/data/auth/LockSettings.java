package dev.vsdeadshot.flashcards.data.auth;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.VisibleForTesting;

/**
 * Whether this device asks for a fingerprint, face or PIN before showing the deck.
 *
 * <p>Its own preferences file, separate from the tokens, and the separation is the point. This
 * is a display preference — losing it costs somebody one toggle. {@code auth.xml} holds a
 * credential and is named individually in the backup exclusion rules; keeping a setting in there
 * would eventually mean somebody widening those rules to back up a preference.
 *
 * <p><strong>Only the setting is persisted, never the fact of having unlocked.</strong> That
 * lives in memory in {@link dev.vsdeadshot.flashcards.ui.lock.AppLock}, so killing the app and
 * reopening it always prompts. Written to disk, the grace period would survive a process death
 * and hand somebody a window in which force-stopping the app is the way past the lock.
 */
public final class LockSettings {

    @VisibleForTesting
    static final String FILE = "lock";

    private static final String KEY_ENABLED = "requireUnlock";

    private final SharedPreferences prefs;

    public LockSettings(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /**
     * Off unless somebody turned it on.
     *
     * <p>Opt-in rather than opt-out, which is not the usual instinct for a security control. A
     * lock that arrived switched on would be a lock nobody chose, on an app holding revision
     * notes, and the first thing it could do is fail on a device with nothing enrolled — leaving
     * a person shut out of their own cards by a default. The protection this offers is modest
     * enough that it should be asked for.
     */
    public boolean enabled() {
        return prefs.getBoolean(KEY_ENABLED, false);
    }

    public void setEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }
}
