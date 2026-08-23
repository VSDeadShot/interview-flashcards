package dev.vsdeadshot.flashcards.ui.lock;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.Before;
import org.junit.Test;

/**
 * When the gate has to be shown.
 *
 * <p>No Robolectric and no {@code BiometricPrompt}: the prompt cannot be exercised off a device,
 * and this is the part that holds a rule which could be wrong without anybody noticing. Splitting
 * the decision out from the dialog is what makes that testable at all.
 *
 * <p>The clock is a parameter for the reason the backend's scheduler takes one — a test that
 * waited a real minute to check a one-minute grace period would be a test nobody runs.
 */
public class AppLockTest {

    private static final Instant NOW = Instant.parse("2026-08-23T09:00:00Z");

    private static Clock at(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    private static Clock afterUnlock(Duration elapsed) {
        return at(NOW.plus(elapsed));
    }

    @Before
    public void forgetPreviousUnlocks() {
        // Static state, so a test that unlocked would otherwise leave the next one unlocked.
        AppLock.relock();
    }

    @Test
    public void aDeviceWithTheSettingOffIsNeverGated() {
        assertFalse("nothing has been asked for, so nothing is imposed",
                AppLock.required(false, at(NOW)));

        AppLock.unlocked(at(NOW));
        assertFalse(AppLock.required(false, afterUnlock(Duration.ofDays(365))));
    }

    @Test
    public void aColdStartIsAlwaysGated() {
        assertTrue("the unlock is held in memory only, so a new process has none — which is what"
                        + " stops force-stopping the app being a way past the gate",
                AppLock.required(true, at(NOW)));
    }

    @Test
    public void unlockingSatisfiesTheGate() {
        AppLock.unlocked(at(NOW));

        assertFalse(AppLock.required(true, at(NOW)));
    }

    /**
     * The reason there is a grace period at all. Re-prompting on every return from another app
     * is the behaviour that teaches somebody to switch a lock off.
     */
    @Test
    public void aBriefTripToAnotherAppDoesNotReprompt() {
        AppLock.unlocked(at(NOW));

        assertFalse("checking something in a browser and coming back is not walking away",
                AppLock.required(true, afterUnlock(Duration.ofSeconds(59))));
    }

    @Test
    public void theGraceExpires() {
        AppLock.unlocked(at(NOW));

        assertTrue("at the boundary the unlock has been spent, not renewed",
                AppLock.required(true, afterUnlock(AppLock.GRACE)));
        assertTrue(AppLock.required(true, afterUnlock(Duration.ofMinutes(5))));
    }

    /**
     * Switching the setting on calls this. Without it, an unlock from earlier in the same minute
     * would leave the app open and turning the lock on would appear to do nothing.
     */
    @Test
    public void relockingTakesEffectImmediately() {
        AppLock.unlocked(at(NOW));
        assertFalse(AppLock.required(true, at(NOW)));

        AppLock.relock();

        assertTrue(AppLock.required(true, at(NOW)));
    }

    /**
     * A device whose clock jumps backwards — a timezone fix, an NTP correction — must not end up
     * permanently unlocked. It reads as still inside the grace, which is the safe direction only
     * because the window is a minute; it is worth knowing that this is the behaviour rather than
     * discovering it.
     */
    @Test
    public void aClockGoingBackwardsStillLocksEventually() {
        AppLock.unlocked(at(NOW));

        assertFalse("inside the window, however the clock got there",
                AppLock.required(true, afterUnlock(Duration.ofSeconds(-30))));
        assertTrue("and a later reading past the window still gates",
                AppLock.required(true, afterUnlock(Duration.ofMinutes(2))));
    }
}
