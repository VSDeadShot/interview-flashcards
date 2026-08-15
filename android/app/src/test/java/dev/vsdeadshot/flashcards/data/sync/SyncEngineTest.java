package dev.vsdeadshot.flashcards.data.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import androidx.room.Room;
import dev.vsdeadshot.flashcards.data.local.CardEntity;
import dev.vsdeadshot.flashcards.data.local.FlashcardsDatabase;
import dev.vsdeadshot.flashcards.data.local.PendingReviewEntity;
import dev.vsdeadshot.flashcards.data.local.TopicEntity;
import dev.vsdeadshot.flashcards.data.remote.ApiClient;
import dev.vsdeadshot.flashcards.data.sync.SyncResult.Outcome;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
 * The sync against both real halves at once — real SQLite through Room, and a real HTTP server
 * on a loopback port. Neither is stubbed, because what these tests are about is the seam
 * between them: which rows a failure leaves behind, and which cards a pull is allowed to touch.
 *
 * <p>Robolectric is here only because Room needs a {@code Context}. The remote tests deliberately
 * do without it; these cannot.
 *
 * <p>{@code MockWebServer} answers in the order responses were enqueued, whatever the path, so
 * every test below enqueues them in the order the engine sends its requests: the outbox first,
 * then {@code /topics}, then {@code /cards}. A test that enqueues fewer than the engine sends is
 * asserting that the engine stopped early.
 */
@RunWith(RobolectricTestRunner.class)
// FlashcardsApp is kept out of this: Robolectric does not create the app's content
// providers, so androidx.startup never initialises WorkManager and onCreate's call to it
// throws. A data-layer test has no business running the app's startup wiring anyway.
@Config(application = Application.class)
public class SyncEngineTest {

    private static final String KEY = "test-key";

    /** What the local scheduler predicted. Nothing the server ever sends below matches it. */
    private static final int PREDICTED_INTERVAL = 99;

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

    // ---- fixtures -------------------------------------------------------------------------

    private void respond(int code, String body) {
        server.enqueue(new MockResponse.Builder()
                .code(code)
                .setHeader("Content-Type", "application/json")
                .body(body)
                .build());
    }

    private void respondWithProblem(int code, String body) {
        server.enqueue(new MockResponse.Builder()
                .code(code)
                .setHeader("Content-Type", "application/problem+json")
                .body(body)
                .build());
    }

    /** A card as the server sends it, distinguishable by its interval alone. */
    private static String serverCard(long id, int intervalDays) {
        return """
                {
                  "id": %d,
                  "topicId": 1,
                  "front": "What is a deadlock?",
                  "back": "Four Coffman conditions",
                  "easeFactor": 2.5,
                  "intervalDays": %d,
                  "repetitions": 2,
                  "lapses": 0,
                  "dueDate": "2026-08-20",
                  "lastReviewedAt": "2026-08-14T09:00:00Z",
                  "archived": false
                }"""
                .formatted(id, intervalDays);
    }

    private static String serverTopic(long id, String name) {
        return """
                {"id": %d, "name": "%s", "slug": "%s", "createdAt": "2026-08-01T00:00:00Z"}"""
                .formatted(id, name, name.toLowerCase().replace(' ', '-'));
    }

    /** The cache as it stands after a review was recorded offline: a prediction, not an answer. */
    private void cacheCardWithPrediction(long id) {
        CardEntity card = new CardEntity();
        card.id = id;
        // A card the server already knows about, which is what a pull would have written.
        card.serverId = id;
        card.topicId = 1L;
        card.front = "What is a deadlock?";
        card.back = "Four Coffman conditions";
        card.easeFactor = 2.5d;
        card.intervalDays = PREDICTED_INTERVAL;
        card.repetitions = 2;
        card.dueDate = LocalDate.of(2026, 11, 21);
        db.cards().upsertAll(List.of(card));
    }

    private void enqueueReview(long cardId, int confidence) {
        PendingReviewEntity review = new PendingReviewEntity();
        review.cardId = cardId;
        review.confidence = confidence;
        review.reviewedAt = Instant.parse("2026-08-14T09:00:00Z");
        review.clientReviewId = UUID.randomUUID();
        db.pendingReviews().enqueue(review);
    }

    private int cachedInterval(long cardId) {
        CardEntity card = db.cards().findById(cardId);
        assertNotNull("card " + cardId + " should still be cached", card);
        return card.intervalDays;
    }

    private static final String RETRYABLE_FALSE = """
            {"type": "about:blank", "title": "Conflict", "status": 409,
             "detail": "clientReviewId already used for a different review",
             "retryable": false}""";

    // ---- order ----------------------------------------------------------------------------

    @Test
    public void theOutboxGoesUpBeforeAnythingComesDown() throws InterruptedException {
        cacheCardWithPrediction(1L);
        enqueueReview(1L, 4);
        respond(200, serverCard(1L, 10));
        respond(200, "[]");
        respond(200, "[" + serverCard(1L, 10) + "]");

        engine.sync();

        assertEquals(
                "the review must be sent before the pull that would otherwise fetch the card's"
                        + " pre-review row",
                "/api/v1/study/1/review",
                server.takeRequest().getTarget());
    }

    @Test
    public void anAcceptedReviewClearsItsRowAndWritesTheServersAnswer() {
        cacheCardWithPrediction(1L);
        enqueueReview(1L, 4);
        respond(200, serverCard(1L, 10));
        // The pull is failed deliberately, so the card below can only have been written by the
        // push. Were the pull allowed to succeed it would write the same card and prove nothing.
        respond(500, "{}");

        SyncResult result = engine.sync();

        assertEquals("the accepted review should no longer be queued", 0, db.pendingReviews().size());
        assertEquals("the card should hold the server's answer, not the prediction",
                10, cachedInterval(1L));
        assertEquals("one review was accepted", 1, result.pushed());
        assertEquals("the pull did not complete", Outcome.FAILED, result.outcome());
    }

    @Test
    public void aFailedPullStillReportsWhatThePushAchieved() {
        cacheCardWithPrediction(1L);
        enqueueReview(1L, 4);
        respond(200, serverCard(1L, 10));
        respond(500, "{}");

        SyncResult result = engine.sync();

        assertEquals("the push happened even though the pull did not", 1, result.pushed());
        assertTrue("a run whose pull failed has work left", result.hasWorkLeft());
    }

    // ---- draining per card ----------------------------------------------------------------

    @Test
    public void aCardTheServerRefusesDoesNotHoldUpAnother() {
        cacheCardWithPrediction(1L);
        cacheCardWithPrediction(2L);
        enqueueReview(1L, 4);
        enqueueReview(2L, 3);
        respond(500, "{}");                    // card 1's review
        respond(200, serverCard(2L, 10));      // card 2's review, sent anyway
        respond(200, "[]");
        respond(200, "[" + serverCard(1L, 6) + "," + serverCard(2L, 10) + "]");

        SyncResult result = engine.sync();

        assertEquals("the second card's review should have been sent and accepted",
                1, result.pushed());
        assertEquals("only the refused card's review is still queued", 1, result.stalled());
        assertEquals(1L, db.pendingReviews().queued().get(0).cardId);
        assertEquals("the stalled card keeps its prediction and is skipped by the pull",
                PREDICTED_INTERVAL, cachedInterval(1L));
        assertEquals("the accepted card takes the server's answer", 10, cachedInterval(2L));
    }

    @Test
    public void aChainThatStallsLeavesTheCardShowingItsLocalPrediction() {
        cacheCardWithPrediction(1L);
        enqueueReview(1L, 4);
        enqueueReview(1L, 5);
        respond(200, serverCard(1L, 10));      // the first review is accepted
        respond(500, "{}");                    // the second is not
        respond(200, "[]");
        respond(200, "[" + serverCard(1L, 6) + "]");

        SyncResult result = engine.sync();

        assertEquals("the accepted review is gone from the outbox", 1, db.pendingReviews().size());
        assertEquals("the unsent one is still queued", 1, result.stalled());
        assertEquals(
                "a half-drained chain must not step the card back to the schedule of the reviews"
                        + " accepted so far — the prediction is the newer truth until the chain"
                        + " empties",
                PREDICTED_INTERVAL,
                cachedInterval(1L));
    }

    @Test
    public void aCardWithAQueuedReviewSurvivesThePullThatSkipsIt() {
        cacheCardWithPrediction(1L);
        enqueueReview(1L, 4);
        respond(500, "{}");                    // the review stalls, so the card stays excluded
        respond(200, "[]");
        respond(200, "[" + serverCard(1L, 6) + "]");

        engine.sync();

        assertEquals("skipped by the upsert", PREDICTED_INTERVAL, cachedInterval(1L));
        assertNotNull(
                "and not deleted by deleteMissing — the server did list it, so its id has to be"
                        + " among the ones kept even though its row was not written",
                db.cards().findById(1L));
    }

    // ---- dispositions ---------------------------------------------------------------------

    @Test
    public void aRetryableFailureKeepsTheRowAndRecordsWhy() {
        cacheCardWithPrediction(1L);
        enqueueReview(1L, 4);
        respond(503, "{}");
        respond(200, "[]");
        respond(200, "[" + serverCard(1L, 6) + "]");

        engine.sync();

        PendingReviewEntity stalled = db.pendingReviews().queued().get(0);
        assertEquals("the review is still queued", 1, db.pendingReviews().size());
        assertEquals("the attempt was counted", 1, stalled.attempts);
        assertNotNull("a queue that is stuck should be able to say why", stalled.lastError);
    }

    @Test
    public void aPermanentFailureDropsTheRowAndThePullRepairsTheCard() {
        cacheCardWithPrediction(1L);
        enqueueReview(1L, 4);
        respondWithProblem(409, RETRYABLE_FALSE);
        respond(200, "[]");
        respond(200, "[" + serverCard(1L, 6) + "]");

        SyncResult result = engine.sync();

        assertEquals("a review the server will never accept cannot stay queued",
                0, db.pendingReviews().size());
        assertEquals(1, result.dropped());
        assertEquals(
                "with nothing queued the card is no longer excluded, so the same run's pull"
                        + " replaces the prediction the server never applied",
                6,
                cachedInterval(1L));
    }

    @Test
    public void aRejectedKeyStopsBeforeAnythingIsPulled() {
        cacheCardWithPrediction(1L);
        cacheCardWithPrediction(2L);
        enqueueReview(1L, 4);
        enqueueReview(2L, 3);
        // Exactly what the running backend sends: the filter rejects before any handler runs,
        // so there is no problem+json and no body at all.
        server.enqueue(new MockResponse.Builder().code(401).build());

        SyncResult result = engine.sync();

        assertEquals(Outcome.STOPPED, result.outcome());
        assertEquals(
                "every request would be rejected identically, so nothing else was attempted",
                1,
                server.getRequestCount());
        assertEquals("both reviews stay queued for a run with a key that works",
                2, db.pendingReviews().size());
        assertEquals(
                "the count has to include the card this loop never reached, not just the rest of"
                        + " the chain it stopped in",
                2,
                result.stalled());
    }

    // ---- the pull -------------------------------------------------------------------------

    @Test
    public void topicsAndCardsArrivingFromTheServerAreCached() {
        respond(200, "[" + serverTopic(1L, "Operating Systems") + "]");
        respond(200, "[" + serverCard(1L, 6) + "]");

        SyncResult result = engine.sync();

        assertEquals(Outcome.OK, result.outcome());
        assertEquals(1, result.topicsWritten());
        assertEquals(1, result.cardsWritten());
        assertEquals("Operating Systems", db.topics().findById(1L).name);
        assertEquals(6, cachedInterval(1L));
        assertTrue("nothing was queued and the pull completed", !result.hasWorkLeft());
    }

    @Test
    public void aCardTheServerNoLongerListsIsRemoved() {
        cacheCardWithPrediction(1L);
        cacheCardWithPrediction(2L);
        respond(200, "[]");
        respond(200, "[" + serverCard(1L, 6) + "]");

        engine.sync();

        assertNotNull("still listed", db.cards().findById(1L));
        assertNull(
                "a pull asks for archived cards too, so one absent from the answer is gone for"
                        + " good rather than merely hidden",
                db.cards().findById(2L));
    }

    @Test
    public void aServerWithNothingLeftEmptiesTheCacheRatherThanFailing() {
        TopicEntity topic = new TopicEntity();
        topic.id = 1L;
        topic.name = "Operating Systems";
        topic.slug = "operating-systems";
        topic.createdAt = Instant.parse("2026-08-01T00:00:00Z");
        db.topics().upsertAll(List.of(topic));
        cacheCardWithPrediction(1L);
        respond(200, "[]");
        respond(200, "[]");

        SyncResult result = engine.sync();

        // Room expands an empty list to `not in ()`, which SQLite rejects outright — so the
        // no-rows case cannot go through deleteMissing at all and has its own path.
        assertEquals(Outcome.OK, result.outcome());
        assertTrue("no topics left", db.topics().findAll().isEmpty());
        assertTrue("no cards left", db.cards().findAllActive().isEmpty());
    }
}
