package dev.vsdeadshot.flashcards.ui.lock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.os.Looper;
import android.view.View;
import androidx.appcompat.widget.Toolbar;
import androidx.room.Room;
import androidx.work.testing.WorkManagerTestInitHelper;
import dev.vsdeadshot.flashcards.R;
import dev.vsdeadshot.flashcards.data.auth.LockSettings;
import dev.vsdeadshot.flashcards.data.auth.PrefsTokenStore;
import dev.vsdeadshot.flashcards.data.local.FlashcardsDatabase;
import dev.vsdeadshot.flashcards.ui.Graph;
import dev.vsdeadshot.flashcards.ui.MainActivity;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The setting, and the half of the gate that can be exercised without a device.
 *
 * <p><strong>What is deliberately not tested here:</strong> a successful unlock. Showing
 * {@code BiometricPrompt} needs a real fingerprint sensor or screen lock, and Robolectric has
 * neither — which is exactly why the decision about <em>whether</em> to gate lives in
 * {@link AppLock} as a plain function of time, where {@code AppLockTest} covers it in full. What
 * remains here is the setting's own behaviour and the refusal that protects somebody from
 * switching on a lock their device cannot open.
 */
@RunWith(RobolectricTestRunner.class)
// FlashcardsApp is kept out for the reason every other activity test keeps it out: Robolectric
// creates no content providers, so androidx.startup never initialises WorkManager.
@Config(application = Application.class)
public class LockGateTest {

    private FlashcardsDatabase db;
    private LockSettings settings;

    @Before
    public void setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(RuntimeEnvironment.getApplication());
        db = Room.inMemoryDatabaseBuilder(
                        RuntimeEnvironment.getApplication(), FlashcardsDatabase.class)
                .allowMainThreadQueries()
                .build();
        Graph.installDatabase(db);
        PrefsTokenStore.reset();
        settings = new LockSettings(RuntimeEnvironment.getApplication());
        settings.setEnabled(false);
        AppLock.relock();
        UnlockPrompt.forceAvailable(null);
    }

    @After
    public void tearDown() {
        settings.setEnabled(false);
        AppLock.relock();
        UnlockPrompt.forceAvailable(null);
        PrefsTokenStore.reset();
        Graph.reset();
        db.close();
    }

    private MainActivity open() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        shadowOf(Looper.getMainLooper()).idle();
        return activity;
    }

    @Test
    public void theLockIsOffUntilSomebodyAsksForIt() {
        assertFalse("a security control that arrives switched on is one nobody chose, and the"
                        + " first thing it could do is shut somebody out of their own cards",
                settings.enabled());
    }

    @Test
    public void withTheLockOffNothingCoversTheApp() {
        MainActivity activity = open();

        assertEquals(View.GONE, activity.findViewById(R.id.lock_overlay).getVisibility());
    }

    @Test
    public void theSettingIsOfferedInTheOverflow() {
        MainActivity activity = open();
        Toolbar toolbar = activity.findViewById(R.id.toolbar);
        activity.invalidateOptionsMenu();
        shadowOf(Looper.getMainLooper()).idle();

        assertNotNull("the only setting the app has, so the overflow is the whole of its home",
                toolbar.getMenu().findItem(R.id.action_lock));
        assertFalse("it reflects the stored setting rather than its own state",
                toolbar.getMenu().findItem(R.id.action_lock).isChecked());
    }

    /**
     * The case the refusal exists for: a device with no biometric and no screen lock. Accepting
     * the setting there would show the gate at the next start with nothing able to dismiss it —
     * and the setting to turn it off again is behind the gate.
     *
     * <p>Forced, because Robolectric answers {@code BIOMETRIC_SUCCESS} to every
     * {@code canAuthenticate} call and so cannot represent this device at all. That was measured
     * rather than assumed, and it is the whole reason {@code forceAvailable} exists.
     */
    @Test
    public void aDeviceThatCannotAuthenticateRefusesTheSetting() {
        UnlockPrompt.forceAvailable(false);
        MainActivity activity = open();
        Toolbar toolbar = activity.findViewById(R.id.toolbar);
        activity.invalidateOptionsMenu();
        shadowOf(Looper.getMainLooper()).idle();

        toolbar.getMenu().performIdentifierAction(R.id.action_lock, 0);
        shadowOf(Looper.getMainLooper()).idle();

        assertFalse("the setting must not stick where it could not be satisfied",
                settings.enabled());
        assertEquals("and nothing covers the app either",
                View.GONE, activity.findViewById(R.id.lock_overlay).getVisibility());
    }

    @Test
    public void aDeviceThatCanAuthenticateAcceptsIt() {
        UnlockPrompt.forceAvailable(true);
        MainActivity activity = open();
        Toolbar toolbar = activity.findViewById(R.id.toolbar);
        activity.invalidateOptionsMenu();
        shadowOf(Looper.getMainLooper()).idle();

        toolbar.getMenu().performIdentifierAction(R.id.action_lock, 0);
        shadowOf(Looper.getMainLooper()).idle();

        assertTrue(settings.enabled());
        assertTrue("switching it on has to lock, not wait out a grace period from earlier",
                AppLock.required(true, java.time.Clock.systemDefaultZone()));
    }

    @Test
    public void theSettingSurvivesBeingWrittenAndReadBack() {
        settings.setEnabled(true);

        assertTrue(new LockSettings(RuntimeEnvironment.getApplication()).enabled());
    }

    /**
     * Turning the lock on has to lock. Without the relock, an unlock from earlier in the same
     * minute would leave the app open and the setting would appear to do nothing.
     */
    @Test
    public void switchingItOnTakesEffectRatherThanWaitingOutTheGrace() {
        AppLock.unlocked(java.time.Clock.systemDefaultZone());
        assertFalse(AppLock.required(true, java.time.Clock.systemDefaultZone()));

        AppLock.relock();

        assertTrue(AppLock.required(true, java.time.Clock.systemDefaultZone()));
    }
}
