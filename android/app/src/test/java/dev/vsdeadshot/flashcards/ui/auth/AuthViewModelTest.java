package dev.vsdeadshot.flashcards.ui.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.os.Looper;
import dev.vsdeadshot.flashcards.R;
import dev.vsdeadshot.flashcards.data.AuthRepository;
import dev.vsdeadshot.flashcards.data.auth.FakeTokenStore;
import dev.vsdeadshot.flashcards.data.remote.ApiClient;
import dev.vsdeadshot.flashcards.ui.auth.AuthViewModel.SignInState;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * What a sign-in in progress says about itself while it waits.
 *
 * <p>A server woken from idle takes about two minutes to answer, and a progress bar on its own
 * for that long reads as an app that has frozen. So once an attempt has gone unanswered for five
 * seconds the state says the server is waking, and it stops saying so the moment an answer comes
 * back either way.
 *
 * <p>The server holds every answer until a test releases it, so "not answered yet" is exact
 * rather than a race against a delay; Robolectric's paused main looper moves the five seconds on
 * independently of the wall clock. Robolectric is here only because the view model posts to the
 * main thread — nothing below builds a screen.
 */
@RunWith(RobolectricTestRunner.class)
// FlashcardsApp is kept out for the usual reason: Robolectric creates no content providers, so
// androidx.startup never initialises WorkManager and onCreate throws.
@Config(application = Application.class)
public class AuthViewModelTest {

    private static final String ISSUED = """
            {
              "accessToken": "access-token",
              "expiresIn": 3600,
              "refreshToken": "refresh-token",
              "refreshExpiresIn": 2592000
            }""";

    private final CountDownLatch answer = new CountDownLatch(1);
    private volatile MockResponse response;

    private MockWebServer server;
    private ExecutorService background;
    private AuthViewModel model;

    @Before
    public void setUp() throws IOException {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                answer.await(30, TimeUnit.SECONDS);
                return response;
            }
        });
        server.start();

        FakeTokenStore tokens = FakeTokenStore.signedOut();
        AuthRepository repository =
                new AuthRepository(ApiClient.auth(server.url("/api/v1/").toString()), tokens);
        background = Executors.newSingleThreadExecutor();
        model = new AuthViewModel(
                RuntimeEnvironment.getApplication(), () -> repository, tokens, background);
    }

    @After
    public void tearDown() {
        answer.countDown();
        server.close();
        background.shutdownNow();
    }

    private void answerWith(MockResponse answered) {
        response = answered;
        answer.countDown();
    }

    private static MockResponse accepted() {
        return new MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "application/json")
                .body(ISSUED)
                .build();
    }

    private SignInState state() {
        return model.signInState().getValue();
    }

    private static void advance(Duration by) {
        shadowOf(Looper.getMainLooper()).idleFor(by);
    }

    /** Waits for the answer to reach the main thread, without moving its clock. */
    private SignInState awaitFinished() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle();
            SignInState now = state();
            if (now != null && !now.running()) {
                return now;
            }
            Thread.sleep(20);
        }
        fail("the sign-in never finished");
        return null;
    }

    @Test
    public void anAttemptUnansweredForFiveSecondsSaysTheServerIsWaking() {
        model.signIn("the passphrase");

        advance(Duration.ofMillis(4_900));
        assertTrue(state().running());
        assertFalse("under five seconds is an ordinary wait, and says nothing extra",
                state().waking());

        advance(Duration.ofMillis(100));
        assertTrue("still running", state().running());
        assertTrue("five seconds without an answer is the server waking, and should say so",
                state().waking());
    }

    @Test
    public void theWakingMessageClearsWhenTheSignInSucceeds() throws Exception {
        model.signIn("the passphrase");
        advance(Duration.ofSeconds(5));
        assertTrue("waking before the answer arrives", state().waking());

        answerWith(accepted());
        SignInState finished = awaitFinished();

        assertTrue(finished.succeeded());
        assertFalse("an answered sign-in is not waiting on anything", finished.waking());
    }

    @Test
    public void theWakingMessageClearsWhenTheSignInIsRefused() throws Exception {
        model.signIn("the wrong passphrase");
        advance(Duration.ofSeconds(5));
        assertTrue("waking before the answer arrives", state().waking());

        answerWith(new MockResponse.Builder().code(401).build());
        SignInState finished = awaitFinished();

        assertEquals("the refusal is what is said now", R.string.auth_error_refused,
                (int) finished.error());
        assertFalse("and not that the server is still waking", finished.waking());
    }

    @Test
    public void aQuickAnswerNeverSaysTheServerIsWaking() throws Exception {
        answerWith(accepted());
        model.signIn("the passphrase");

        SignInState finished = awaitFinished();
        advance(Duration.ofSeconds(10));

        assertTrue(finished.succeeded());
        assertFalse("five seconds passing after the answer must not bring the message back",
                state().waking());
    }
}
