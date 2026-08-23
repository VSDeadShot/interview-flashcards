package dev.vsdeadshot.flashcards.data.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import dev.vsdeadshot.flashcards.data.auth.TokenStore.AuthState;
import dev.vsdeadshot.flashcards.data.auth.TokenStore.SignedOutReason;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The real {@link android.content.SharedPreferences} under Robolectric, for the same reason the
 * database tests run real SQLite: a stand-in would agree with whatever this class assumed.
 */
@RunWith(RobolectricTestRunner.class)
// FlashcardsApp is kept out for the reason the database tests keep it out: Robolectric creates
// no content providers, so androidx.startup never initialises WorkManager and onCreate throws.
@Config(application = Application.class)
public class PrefsTokenStoreTest {

    private Application app;
    private PrefsTokenStore store;

    @Before
    public void openStore() {
        app = RuntimeEnvironment.getApplication();
        PrefsTokenStore.reset();
        app.getSharedPreferences(PrefsTokenStore.FILE, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
        store = PrefsTokenStore.get(app);
    }

    @After
    public void closeStore() {
        // The instance is process-wide and the file outlives this Robolectric application, so
        // leaving either behind would hand the next test class somebody else's session.
        store.signOut(SignedOutReason.SIGNED_OUT);
        PrefsTokenStore.reset();
    }

    @Test
    public void aFreshInstallHasNeverSignedIn() {
        AuthState state = store.state();

        assertFalse(state.signedIn());
        assertEquals("never signed in is not the same event as a session that ended",
                SignedOutReason.NEVER, state.reason());
        assertNull(store.accessToken());
        assertNull(store.refreshToken());
    }

    @Test
    public void aStoredPairSurvivesAndReadsAsSignedIn() {
        store.save("access", "refresh");

        assertEquals("access", store.accessToken());
        assertEquals("refresh", store.refreshToken());
        assertTrue(store.state().signedIn());
    }

    @Test
    public void everyScreenAndEverySyncSeeOneStore() {
        assertSame("a screen watching for a change has to be watching the instance the "
                        + "authenticator writes to, and nothing in the manifest asks for a "
                        + "second process",
                store, PrefsTokenStore.get(app));
    }

    @Test
    public void signingOutKeepsWhyAndNotTheTokens() {
        store.save("access", "refresh");

        store.signOut(SignedOutReason.SESSION_EXPIRED);

        assertNull(store.accessToken());
        assertNull(store.refreshToken());
        assertFalse(store.state().signedIn());
        assertEquals("the reason is the only thing a background sync can leave behind for a "
                        + "screen to find",
                SignedOutReason.SESSION_EXPIRED, store.state().reason());
    }

    @Test
    public void signingInAgainClearsTheReasonItWasOut() {
        store.signOut(SignedOutReason.SESSION_EXPIRED);

        store.save("access", "refresh");
        store.signOut(SignedOutReason.SIGNED_OUT);

        assertEquals("a stale reason would have the banner apologise for the wrong thing",
                SignedOutReason.SIGNED_OUT, store.state().reason());
    }

    @Test
    public void aListenerIsToldWhenTheSessionEnds() {
        AtomicReference<AuthState> seen = new AtomicReference<>();
        TokenStore.Listener listener = seen::set;
        store.addListener(listener);

        store.save("access", "refresh");
        assertTrue("a sign-in reaches the toolbar", seen.get().signedIn());

        store.signOut(SignedOutReason.SESSION_EXPIRED);
        assertFalse("and so does a session ending in a background sync", seen.get().signedIn());
        assertEquals(SignedOutReason.SESSION_EXPIRED, seen.get().reason());

        store.removeListener(listener);
    }

    /**
     * SharedPreferences holds its listeners weakly, which is why this class keeps its own map of
     * them. Without that the delegate would be the only strong reference, collectable at an
     * unpredictable moment, and the screen would simply stop updating.
     */
    @Test
    public void aRemovedListenerHearsNothingFurther() {
        AtomicReference<AuthState> seen = new AtomicReference<>();
        TokenStore.Listener listener = seen::set;
        store.addListener(listener);
        store.save("access", "refresh");
        seen.set(null);

        store.removeListener(listener);
        store.signOut(SignedOutReason.SIGNED_OUT);

        assertNull("a view model that has been cleared must not be written into", seen.get());
    }
}
