package dev.vsdeadshot.flashcards.data.sync;

import static org.junit.Assert.assertEquals;

import android.app.Application;
import android.content.Context;
import androidx.annotation.NonNull;
import androidx.room.Room;
import androidx.work.ListenableWorker;
import androidx.work.ListenableWorker.Result;
import androidx.work.WorkerFactory;
import androidx.work.WorkerParameters;
import androidx.work.testing.SynchronousExecutor;
import androidx.work.testing.TestWorkerBuilder;
import dev.vsdeadshot.flashcards.data.local.FlashcardsDatabase;
import dev.vsdeadshot.flashcards.data.local.PendingReviewEntity;
import dev.vsdeadshot.flashcards.data.remote.ApiClient;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * What a run of the sync asks WorkManager to do next.
 *
 * <p>The engine underneath is the real one, against the same loopback server and in-memory
 * SQLite {@code SyncEngineTest} uses. A stubbed engine would only be asserting that a two-line
 * mapping maps, whereas the question worth answering is which real failures come back sooner.
 */
@RunWith(RobolectricTestRunner.class)
// FlashcardsApp is kept out of this: Robolectric does not create the app's content
// providers, so androidx.startup never initialises WorkManager and onCreate's call to it
// throws. A data-layer test has no business running the app's startup wiring anyway.
@Config(application = Application.class)
public class SyncWorkerTest {

    private static final String KEY = "test-key";

    private MockWebServer server;
    private FlashcardsDatabase db;
    private SyncEngine engine;

    @Before
    public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        db = Room.inMemoryDatabaseBuilder(
                        RuntimeEnvironment.getApplication(), FlashcardsDatabase.class)
                .allowMainThreadQueries()
                .build();
        engine = new SyncEngine(ApiClient.create(server.url("/api/v1/").toString(), KEY), db);
    }

    @After
    public void tearDown() {
        db.close();
        server.close();
    }

    @Test
    public void aRunWithNothingLeftOverIsDone() {
        respond(200, "[" + TOPIC + "]");
        respond(200, "[" + CARD + "]");

        assertEquals("A drained outbox and a completed pull leave nothing to come back for",
                Result.success(), worker().doWork());
    }

    @Test
    public void aReviewStillInTheOutboxBringsTheNextAttemptForward() {
        enqueueReview(7L);
        respond(500, "{}");
        respond(200, "[" + TOPIC + "]");
        respond(200, "[" + CARD + "]");

        assertEquals("A review the server could not take yet should be retried on a backoff,"
                        + " not left until the next period",
                Result.retry(), worker().doWork());
    }

    @Test
    public void aPullThatDidNotFinishBringsTheNextAttemptForward() {
        respond(500, "{}");

        assertEquals("A cache that is a pull behind should be repaired sooner than the period",
                Result.retry(), worker().doWork());
    }

    @Test
    public void aRejectedKeyIsNotWorthRetrying() {
        enqueueReview(7L);
        // 401 carries no body at all: the backend's filter rejects before any handler runs.
        server.enqueue(new MockResponse.Builder().code(401).build());

        assertEquals("Nothing a backoff timer does will make a refused key acceptable",
                Result.failure(), worker().doWork());
    }

    // ---- fixtures -------------------------------------------------------------------------

    private static final String TOPIC = """
            {"id": 1, "name": "OS", "slug": "os", "createdAt": "2026-08-01T00:00:00Z"}""";

    private static final String CARD = """
            {
              "id": 7,
              "topicId": 1,
              "front": "What is a deadlock?",
              "back": "Four Coffman conditions",
              "easeFactor": 2.5,
              "intervalDays": 6,
              "repetitions": 2,
              "lapses": 0,
              "dueDate": "2026-08-20",
              "lastReviewedAt": "2026-08-14T09:00:00Z",
              "archived": false
            }""";

    private void respond(int code, String body) {
        server.enqueue(new MockResponse.Builder()
                .code(code)
                .setHeader("Content-Type", "application/json")
                .body(body)
                .build());
    }

    private void enqueueReview(long cardId) {
        PendingReviewEntity review = new PendingReviewEntity();
        review.cardId = cardId;
        review.confidence = 4;
        review.reviewedAt = Instant.parse("2026-08-15T08:00:00Z");
        review.clientReviewId = UUID.randomUUID();
        db.pendingReviews().enqueue(review);
    }

    /**
     * The worker WorkManager would build, except for the engine. {@code WorkerParameters} has no
     * public constructor, so the only way to get a real one is to let the test builder make it
     * and hand back the worker through a factory.
     */
    private SyncWorker worker() {
        return TestWorkerBuilder.from(
                        RuntimeEnvironment.getApplication(),
                        SyncWorker.class,
                        new SynchronousExecutor())
                .setWorkerFactory(new WorkerFactory() {
                    @Override
                    public ListenableWorker createWorker(
                            @NonNull Context context,
                            @NonNull String workerClassName,
                            @NonNull WorkerParameters parameters) {
                        return new SyncWorker(context, parameters, engine);
                    }
                })
                .build();
    }
}
