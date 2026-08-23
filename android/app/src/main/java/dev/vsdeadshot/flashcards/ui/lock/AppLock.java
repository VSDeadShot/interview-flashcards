package dev.vsdeadshot.flashcards.ui.lock;

import androidx.annotation.VisibleForTesting;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Whether the gate has to be shown, and when it stops being satisfied.
 *
 * <p>Deliberately separate from the prompt itself and from the activity, because this is the only
 * part with a rule in it worth testing. {@code BiometricPrompt} cannot be exercised off a device;
 * this can, and it holds the decision that would actually be wrong in a way nobody noticed.
 *
 * <p><strong>What this protects, stated plainly, because it is easy to overestimate.</strong> It
 * is a gate on the user interface. It does not encrypt the tokens, and it is not bound to a
 * Keystore key — that was decided against on purpose, since an auth-bound key cannot be used by
 * the background sync, which by definition runs when nobody is present to authenticate. So this
 * stops somebody who has picked up an unlocked phone from reading the deck or acting through the
 * app. It does nothing against anyone able to read the app's private storage, and the device's
 * own lock screen remains the control that matters for a lost phone.
 */
public final class AppLock {

    /**
     * How long an unlock lasts.
     *
     * <p>A minute, which is a compromise with a shape worth naming. Zero would re-prompt every
     * time somebody switched away to check something and came back, which is the behaviour that
     * teaches people to turn a lock off. An hour would make it decorative. A minute survives an
     * app switch and a notification, and does not survive walking away from the desk.
     */
    @VisibleForTesting
    static final Duration GRACE = Duration.ofMinutes(1);

    /**
     * In memory only, and never written to disk. A persisted timestamp would survive the process
     * dying, which would make force-stopping the app a way through the gate rather than a way to
     * meet it.
     */
    private static volatile Instant unlockedAt;

    private AppLock() {
    }

    /**
     * @param enabled whether the setting is on; passed in rather than read here so this class
     *     needs no {@code Context} and stays testable as a plain function of time.
     */
    public static boolean required(boolean enabled, Clock clock) {
        if (!enabled) {
            return false;
        }
        Instant last = unlockedAt;
        return last == null || !clock.instant().isBefore(last.plus(GRACE));
    }

    public static void unlocked(Clock clock) {
        unlockedAt = clock.instant();
    }

    /**
     * Forgets the unlock, so the next check prompts again.
     *
     * <p>Called when the setting is switched on, so that turning the lock on locks the app rather
     * than leaving it open until the grace period happens to lapse.
     */
    public static void relock() {
        unlockedAt = null;
    }
}
