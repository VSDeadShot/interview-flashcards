package dev.vsdeadshot.flashcards.data.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import androidx.room.Room;
import dev.vsdeadshot.flashcards.data.CardRepository;
import dev.vsdeadshot.flashcards.data.local.CardEntity;
import dev.vsdeadshot.flashcards.data.local.FlashcardsDatabase;
import dev.vsdeadshot.flashcards.data.local.PendingReviewEntity;
import dev.vsdeadshot.flashcards.data.local.TopicEntity;
import dev.vsdeadshot.flashcards.data.remote.ApiClient;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
 * Cards written on this device reaching the server, and finding their way back.
 *
 * <p>Separate from {@code SyncEngineTest} because the questions are different ones. A review is
 * an event that can be lost; a card is the content itself, so the interesting cases here are
 * what happens when it cannot be sent — and what stops it arriving twice when it was sent
 * successfully and nobody heard.
 *
 * <p>{@code MockWebServer} answers in the order responses were enqueued, whatever the path, so
 * each test enqueues them in the order the engine sends: creates, then reviews, then
 * {@code /topics}, then {@code /cards}. Every test ends with a pull, and the pull is answered
 * with a listing consistent with what the push just did — an empty one is not a shortcut, it is
 * a server saying everything was deleted, and the cache would rightly act on it.
 */
@RunWith(RobolectricTestRunner.class)
// FlashcardsApp is kept out of this: Robolectric does not create the app's content
// providers, so androidx.startup never initialises WorkManager and onCreate's call to it
// throws. A data-layer test has no business running the app's startup wiring anyway.
@Config(application = Application.class)
public class SyncEngineCreateTest {

    private static final String KEY = "test-key";
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 17);

    /** A value nothing the server sends below ever matches. */
    private static final int PREDICTED_INTERVAL = 99;

    private MockWebServer server;
    private FlashcardsDatabase db;
    private SyncEngine engine;
    private CardRepository cards;

    @Before
    public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        db = Room.inMemoryDatabaseBuilder(
                        RuntimeEnvironment.getApplication(), FlashcardsDatabase.class)
                .allowMainThreadQueries()
                .build();
        engine = new SyncEngine(ApiClient.create(server.url("/api/v1/").toString(), KEY), db);
        cards = new CardRepository(
                db, Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC));
        cacheTopic();
    }

    @After
    public void tearDown() {
        db.close();
        server.close();
    }

    // ---- the happy path -------------------------------------------------------------------

    @Test
    public void anAcceptedCardKeepsItsLocalIdAndGainsTheServersOne() {
        CardEntity written = cards.create(1L, "What is a deadlock?", "Four conditions");
        respond(201, serverCard(42L, written.clientCardId));
        respondToPullListing(serverCard(42L, written.clientCardId));

        SyncResult result = engine.sync();

        CardEntity synced = db.cards().findById(written.id);
        assertNotNull("the row is still the row it was", synced);
        assertEquals("now carrying the id the server gave it",
                Long.valueOf(42L), synced.serverId);
        assertEquals("one card was created", 1, result.created());
        assertNull("and nothing about it is in error", synced.syncError);
        assertEquals("with no second row for the same card",
                1, db.cards().findAllActive().size());
    }

    @Test
    public void aCardGoesUpBeforeTheReviewThatWasAnsweredOnIt() throws InterruptedException {
        CardEntity written = cards.create(1L, "front", "back");
        enqueueReview(written.id);
        respond(201, serverCard(42L, written.clientCardId));
        respond(200, serverCard(42L, written.clientCardId));
        respondToPullListing(serverCard(42L, written.clientCardId));

        SyncResult result = engine.sync();

        assertEquals("the create is sent first",
                "/api/v1/cards", server.takeRequest().getTarget());
        assertEquals("and the review follows, addressed to the id the create just returned",
                "/api/v1/study/42/review", server.takeRequest().getTarget());
        assertEquals(1, result.created());
        assertEquals("so a card written and studied offline syncs in one run", 1, result.pushed());
        assertEquals(0, db.pendingReviews().size());
    }

    // ---- when the card cannot be sent ------------------------------------------------------

    @Test
    public void aCardTheServerRefusesIsParkedRatherThanDeleted() {
        CardEntity written = cards.create(1L, "front", "back");
        respondWithProblem(404, TOPIC_GONE);
        respondToEmptyPull();

        SyncResult result = engine.sync();

        CardEntity parked = db.cards().findById(written.id);
        assertNotNull("the only copy of what the user wrote is still here", parked);
        assertNotNull("with the server's reason on it", parked.syncError);
        assertEquals("counted as blocked, not as dropped", 1, result.blocked());
        assertEquals("and nothing was discarded", 0, result.dropped());
    }

    /**
     * The reason {@code blocked} is a separate count. A parked card is outstanding work, but no
     * backoff will change the server's answer — only a person editing the card will — so a
     * worker that treated it as stalled would retry for as long as the card sat there.
     */
    @Test
    public void aParkedCardDoesNotKeepTheWorkerComingBack() {
        cards.create(1L, "front", "back");
        respondWithProblem(404, TOPIC_GONE);
        respondToEmptyPull();

        SyncResult result = engine.sync();

        assertEquals("nothing is waiting on a network", 0, result.stalled());
        assertFalse("so there is nothing to come back for", result.hasWorkLeft());
    }

    @Test
    public void aParkedCardIsNotOfferedAgainOnTheNextRun() {
        cards.create(1L, "front", "back");
        respondWithProblem(404, TOPIC_GONE);
        respondToEmptyPull();
        engine.sync();

        // A pull and nothing else. A second create request would consume the topics response
        // and the assertions below would be reading the wrong answers.
        respondToEmptyPull();
        SyncResult second = engine.sync();

        assertEquals("the card is not sent again", 0, second.created());
        assertEquals("nor counted as work in progress", 0, second.stalled());
        assertEquals("the run completed", SyncResult.Outcome.OK, second.outcome());
    }

    @Test
    public void editingAParkedCardOffersItAgain() {
        CardEntity written = cards.create(1L, "front", "back");
        respondWithProblem(404, TOPIC_GONE);
        respondToEmptyPull();
        engine.sync();

        cards.edit(written.id, 1L, "a shorter front", "back");

        assertNull("the reason is cleared, which is what re-arms the create",
                db.cards().findById(written.id).syncError);
        assertEquals("and the card is offered again", 1, db.cards().pendingCreates().size());
    }

    @Test
    public void aRetryableFailureLeavesTheCardToBeSentAgain() {
        CardEntity written = cards.create(1L, "front", "back");
        respond(503, "{}");
        respondToEmptyPull();

        SyncResult result = engine.sync();

        assertNull("still unsent", db.cards().findById(written.id).serverId);
        assertNull("and not parked — a 503 says nothing about the card",
                db.cards().findById(written.id).syncError);
        assertEquals("it is waiting on a network", 1, result.stalled());
        assertTrue("so the worker should come back", result.hasWorkLeft());
    }

    @Test
    public void aReviewOfACardThatIsNotOnTheServerYetWaitsForIt() {
        CardEntity written = cards.create(1L, "front", "back");
        enqueueReview(written.id);
        respond(503, "{}");                      // the create stalls
        respondToEmptyPull();

        SyncResult result = engine.sync();

        assertEquals("the review cannot be addressed yet, so it stays queued",
                1, db.pendingReviews().size());
        assertEquals("counted alongside the create it is waiting on", 2, result.stalled());
        assertEquals("and nothing was sent for it", 0, result.pushed());
    }

    @Test
    public void aReviewOfAParkedCardIsBlockedRatherThanRetriedForever() {
        CardEntity written = cards.create(1L, "front", "back");
        enqueueReview(written.id);
        respondWithProblem(404, TOPIC_GONE);
        respondToEmptyPull();

        SyncResult result = engine.sync();

        assertEquals("the review is still the only record that it happened",
                1, db.pendingReviews().size());
        assertEquals("but it can never be sent while its card cannot be made",
                2, result.blocked());
        assertFalse("so it must not keep the worker awake", result.hasWorkLeft());
    }

    // ---- the merge ------------------------------------------------------------------------

    /**
     * The case the echoed key exists for. The create was accepted and the response was lost, so
     * this client still has the card queued — and the pull in the same run returns it under a
     * server id that means nothing here.
     */
    @Test
    public void aCreateWhoseAnswerWasLostIsRecognisedByItsKeyInsteadOfDuplicated() {
        CardEntity written = cards.create(1L, "front", "back");
        // A create the client does not hear the answer to. Whether the server committed it is
        // exactly what this client cannot tell — which is why the key exists — so a failure it
        // would retry stands in for the response that went missing.
        respond(503, "{}");
        // ...and the pull in the same run lists the card, because the server did commit it.
        respondToPullListing(serverCard(42L, written.clientCardId));

        engine.sync();

        assertEquals("there is one card, not two", 1, db.cards().findAllActive().size());
        CardEntity merged = db.cards().findById(written.id);
        assertNotNull("and it is the row that was already here", merged);
        assertEquals("now knowing the id the lost response would have told it",
                Long.valueOf(42L), merged.serverId);
        assertEquals("so it is no longer waiting to be created",
                0, db.cards().pendingCreates().size());
    }

    @Test
    public void aCardFromSomewhereElseIsStillInsertedNormally() {
        respondToPullListing(serverCard(7L, UUID.randomUUID()));

        engine.sync();

        assertNotNull("a key this device did not mint names no row here, so the card is new",
                db.cards().findById(7L));
        assertEquals(1, db.cards().findAllActive().size());
    }

    /**
     * A merged card keeps a negative local id, and {@code cardIdsAwaitingSync()} answers in local
     * ids. Comparing those against the id the server sent would never match, and the pull would
     * overwrite the prediction of a card whose review has not gone yet.
     */
    @Test
    public void aMergedCardWithAQueuedReviewIsStillSkippedByThePull() {
        CardEntity written = cards.create(1L, "front", "back");
        respond(201, serverCard(42L, written.clientCardId));
        respondToPullListing(serverCard(42L, written.clientCardId));
        engine.sync();

        // The card now has a negative local id and a server id — the shape only a card written
        // here ever takes. A review of it is answered offline, leaving a local prediction.
        CardEntity synced = db.cards().findById(written.id);
        synced.intervalDays = PREDICTED_INTERVAL;
        db.cards().update(synced);
        enqueueReview(written.id);

        respond(503, "{}");                      // the review stalls, so the card stays excluded
        respondToPullListing(serverCardWithInterval(42L, written.clientCardId, 6));
        engine.sync();

        assertEquals(
                "cardIdsAwaitingSync answers in local ids; matching those against the id the"
                        + " server sent would never hit, and the prediction would be overwritten",
                PREDICTED_INTERVAL,
                db.cards().findById(written.id).intervalDays);
    }

    // ---- fixtures -------------------------------------------------------------------------

    private static final String TOPIC_GONE = """
            {"type": "about:blank", "title": "Not Found", "status": 404,
             "detail": "no topic with id 1"}""";

    private static final String TOPIC = """
            {"id": 1, "name": "Operating Systems", "slug": "operating-systems",
             "createdAt": "2026-01-01T00:00:00Z"}""";

    /** The pull asks for the streak last; every run below answers it so nothing is left waiting. */
    private static final String STATS = """
            {"totalCards": 1, "dueToday": 0, "reviewedToday": 0, "currentStreakDays": 3,
             "byTopic": []}""";

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

    /** The pull, answering with the cached topic still present and the cards given. */
    private void respondToPullListing(String... cards) {
        respond(200, "[" + TOPIC + "]");
        respond(200, "[" + String.join(",", cards) + "]");
        respond(200, STATS);
    }

    /** The pull for a run whose card never reached the server, so the server lists none. */
    private void respondToEmptyPull() {
        respondToPullListing();
    }

    private static String serverCard(long id, UUID clientCardId) {
        return serverCardWithInterval(id, clientCardId, 0);
    }

    private static String serverCardWithInterval(long id, UUID clientCardId, int intervalDays) {
        return """
                {
                  "id": %d,
                  "topicId": 1,
                  "front": "front",
                  "back": "back",
                  "easeFactor": 2.5,
                  "intervalDays": %d,
                  "repetitions": 0,
                  "lapses": 0,
                  "dueDate": "2026-03-17",
                  "lastReviewedAt": null,
                  "archived": false,
                  "clientCardId": %s
                }"""
                .formatted(id, intervalDays,
                        clientCardId == null ? "null" : "\"" + clientCardId + "\"");
    }

    private void cacheTopic() {
        TopicEntity topic = new TopicEntity();
        topic.id = 1L;
        topic.name = "Operating Systems";
        topic.slug = "operating-systems";
        topic.createdAt = Instant.parse("2026-01-01T00:00:00Z");
        db.topics().upsertAll(List.of(topic));
    }

    private void enqueueReview(long localCardId) {
        PendingReviewEntity review = new PendingReviewEntity();
        review.cardId = localCardId;
        review.confidence = 4;
        review.reviewedAt = Instant.parse("2026-03-17T09:00:00Z");
        review.clientReviewId = UUID.randomUUID();
        db.pendingReviews().enqueue(review);
    }
}
