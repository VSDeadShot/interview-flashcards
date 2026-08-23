package dev.vsdeadshot.flashcards.ui.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.widget.Toolbar;
import androidx.navigation.fragment.NavHostFragment;
import androidx.room.Room;
import androidx.work.testing.WorkManagerTestInitHelper;
import dev.vsdeadshot.flashcards.R;
import dev.vsdeadshot.flashcards.data.auth.PrefsTokenStore;
import dev.vsdeadshot.flashcards.data.auth.TokenStore;
import dev.vsdeadshot.flashcards.data.auth.TokenStore.SignedOutReason;
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
 * How a dead session reaches a screen.
 *
 * <p>This is the test for the gap {@code CLAUDE.md} has recorded since the sync landed: a
 * rejected credential cannot travel back through {@code WorkInfo}, because WorkManager stores no
 * output data for periodic work and routes success and failure through one reset. Before tokens
 * that cost little — a wrong API key was a broken build, noticed at once and by one person. A
 * token expires after thirty days on a device that has been working perfectly, and the only
 * symptom is an outbox that has quietly stopped draining.
 *
 * <p>So the store is the channel, and this is what proves something is listening to it.
 */
@RunWith(RobolectricTestRunner.class)
// FlashcardsApp is kept out for the reason every other activity test keeps it out: Robolectric
// creates no content providers, so androidx.startup never initialises WorkManager.
@Config(application = Application.class)
public class AuthBannerTest {

    private FlashcardsDatabase db;
    private TokenStore tokens;

    @Before
    public void setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(RuntimeEnvironment.getApplication());
        db = Room.inMemoryDatabaseBuilder(
                        RuntimeEnvironment.getApplication(), FlashcardsDatabase.class)
                .allowMainThreadQueries()
                .build();
        Graph.installDatabase(db);
        PrefsTokenStore.reset();
        tokens = PrefsTokenStore.get(RuntimeEnvironment.getApplication());
        tokens.signOut(SignedOutReason.NEVER);
    }

    @After
    public void tearDown() {
        tokens.signOut(SignedOutReason.NEVER);
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
    public void aSignedOutDeviceIsToldSyncingIsPaused() {
        MainActivity activity = open();

        View banner = activity.findViewById(R.id.auth_banner);
        assertEquals(View.VISIBLE, banner.getVisibility());
        assertEquals(activity.getString(R.string.auth_banner_never),
                ((TextView) banner).getText().toString());
    }

    @Test
    public void aSessionThatEndedSaysSoRatherThanReadingAsNeverSignedIn() {
        tokens.save("access", "refresh");
        MainActivity activity = open();
        assertEquals("nothing is wrong, so nothing is said",
                View.GONE, activity.findViewById(R.id.auth_banner).getVisibility());

        // What TokenAuthenticator writes when a refresh token comes back refused — from a
        // background sync, with no screen involved and nothing else able to report it.
        tokens.signOut(SignedOutReason.SESSION_EXPIRED);
        shadowOf(Looper.getMainLooper()).idle();

        View banner = activity.findViewById(R.id.auth_banner);
        assertEquals("a session ending while nobody was looking still has to surface",
                View.VISIBLE, banner.getVisibility());
        assertEquals(activity.getString(R.string.auth_banner_expired),
                ((TextView) banner).getText().toString());
    }

    @Test
    public void tappingTheBannerOpensSignIn() {
        MainActivity activity = open();

        activity.findViewById(R.id.auth_banner).performClick();
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals(R.id.signInFragment, currentDestination(activity));
    }

    /**
     * Signing in is reachable from the overflow as well as from the banner. The banner is gone
     * as soon as there is a session, and somebody who wants to sign in on a device that already
     * has one — or to sign out — needs a way there that does not depend on something being wrong.
     */
    @Test
    public void theOverflowOffersExactlyOneOfSignInAndSignOut() {
        MainActivity activity = open();
        Toolbar toolbar = activity.findViewById(R.id.toolbar);
        activity.invalidateOptionsMenu();
        shadowOf(Looper.getMainLooper()).idle();

        assertNotNull(toolbar.getMenu().findItem(R.id.action_sign_in));
        assertTrue("signed out, so the way in is the one offered",
                toolbar.getMenu().findItem(R.id.action_sign_in).isVisible());
        assertFalse("and there is no session to end",
                toolbar.getMenu().findItem(R.id.action_sign_out).isVisible());
    }

    @Test
    public void aSignedInDeviceIsOfferedTheWayOutInstead() {
        tokens.save("access", "refresh");
        MainActivity activity = open();
        Toolbar toolbar = activity.findViewById(R.id.toolbar);
        activity.invalidateOptionsMenu();
        shadowOf(Looper.getMainLooper()).idle();

        assertFalse(toolbar.getMenu().findItem(R.id.action_sign_in).isVisible());
        assertTrue(toolbar.getMenu().findItem(R.id.action_sign_out).isVisible());
    }

    /**
     * The whole reason sign-in is a destination rather than a gate: nothing waits for it. Every
     * screen reads Room, so a device with a deck on it studies, writes and archives with no token
     * at all.
     */
    @Test
    public void beingSignedOutDoesNotStandBetweenAnybodyAndTheApp() {
        MainActivity activity = open();

        assertEquals("the app opens where it always opens",
                R.id.studyFragment, currentDestination(activity));
    }

    private int currentDestination(MainActivity activity) {
        NavHostFragment host = (NavHostFragment)
                activity.getSupportFragmentManager().findFragmentById(R.id.nav_host);
        return host.getNavController().getCurrentDestination().getId();
    }
}
