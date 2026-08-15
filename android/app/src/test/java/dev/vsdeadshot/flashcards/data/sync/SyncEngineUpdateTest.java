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
 * Edits and archives finding their way to the server.
 *
 * <p>An edit is a state rather than an event, so the record of one is a marker on the row and the
 * request is derived from it — an archived card is a {@code DELETE} and any other a {@code PUT}.
 * What these tests are mostly about is the marker: when it goes up, when it comes down, and the
 * one case where taking it down would lose what the user typed.
 */
@RunWith(RobolectricTestRunner.class)
// FlashcardsApp is kept out of this: Robolectric does not create the app's content
// providers, so androidx.startup never initialises WorkManager and onCreate's call to it
// throws. A data-layer test has no business running the app's startup wiring anyway.
@Config(application = Application.class)
public class SyncEngineUpdateTest {

    private static final String KEY = "test-key";
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 17);

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

    // ---- edits ------------------------------------------------------------------------------

    @Test
    public void anEditIsSentAsAnUpdateAndTheMarkerComesDown() throws InterruptedException {
        cacheServerCard(7L);
        cards.edit(7L, 1L, "fixed", "back");

        respond(200, serverCard(7L, "fixed", "back", false));
        respondToPullListing(serverCard(7L, "fixed", "back", false));
        SyncResult result = engine.sync();

        assertEquals("sent as a replacement of the whole card",
                "/api/v1/cards/7", server.takeRequest().getTarget());
        assertEquals("one card's change was taken", 1, result.updated());
        assertNull("and the row no longer differs from the server's",
                db.cards().findById(7L).pendingSince);
    }

    @Test
    public void anEditIsNotOverwrittenByThePullThatFollowsItsOwnFailure() {
        cacheServerCard(7L);
        cards.edit(7L, 1L, "fixed", "back");

        respond(503, "{}");
        // The server still has the old text, and would happily hand it back.
        respondToPullListing(serverCard(7L, "stale", "back", false));
        engine.sync();

        assertEquals("an unsent edit is the newer truth, so the pull leaves the row alone",
                "fixed", db.cards().findById(7L).front);
        assertNotNull("and it is still waiting to go", db.cards().findById(7L).pendingSince);
    }

    /**
     * The reason the marker is cleared by comparison rather than by id. Clearing it outright
     * would drop it on content the user changed while the request was in flight, and that edit
     * would never be sent — silently, because the row would look synced.
     */
    @Test
    public void anEditThatLandsWhileTheLastOneIsInFlightIsNotLost() {
        cacheServerCard(7L);
        cards.edit(7L, 1L, "first", "back");

        // The server takes the first edit; the second is typed before the run gets to clear the
        // marker, which is what the dispatcher below stands in for.
        server.setDispatcher(new mockwebserver3.Dispatcher() {
            @Override
            public MockResponse dispatch(mockwebserver3.RecordedRequest request) {
                if (request.getTarget().equals("/api/v1/cards/7")) {
                    cards.edit(7L, 1L, "second", "back");
                    return json(200, serverCard(7L, "first", "back", false));
                }
                if (request.getTarget().startsWith("/api/v1/topics")) {
                    return json(200, "[" + TOPIC + "]");
                }
                return json(200, "[" + serverCard(7L, "first", "back", false) + "]");
            }
        });

        engine.sync();

        CardEntity card = db.cards().findById(7L);
        assertEquals("the newer edit is still the one on the row", "second", card.front);
        assertNotNull("and it is still marked, so the next run sends it", card.pendingSince);
    }

    @Test
    public void aRetryableFailureLeavesTheEditToBeSentAgain() {
        cacheServerCard(7L);
        cards.edit(7L, 1L, "fixed", "back");

        respond(503, "{}");
        respondToPullListing(serverCard(7L, "stale", "back", false));
        SyncResult result = engine.sync();

        assertEquals("waiting on a network", 1, result.stalled());
        assertTrue("so the worker should come back", result.hasWorkLeft());
        assertNull("and nothing about the card is in error", db.cards().findById(7L).syncError);
    }

    @Test
    public void anEditTheServerPermanentlyRefusesIsParkedRatherThanRetriedForever() {
        cacheServerCard(7L);
        cards.edit(7L, 1L, "fixed", "back");

        respondWithProblem(404, CARD_GONE);
        respondToPullListing(serverCard(7L, "stale", "back", false));
        SyncResult result = engine.sync();

        CardEntity card = db.cards().findById(7L);
        assertEquals("what the user typed is still here", "fixed", card.front);
        assertNotNull("with the server's reason on it", card.syncError);
        assertEquals("counted as needing a person, not a retry", 1, result.blocked());
        assertFalse("so it must not keep the worker awake", result.hasWorkLeft());
    }

    // ---- archives ---------------------------------------------------------------------------

    @Test
    public void anArchivedCardIsSentAsADeleteAndTheMarkerComesDown()
            throws InterruptedException {
        cacheServerCard(7L);
        cards.archive(7L);

        server.enqueue(new MockResponse.Builder().code(204).build());
        respondToPullListing(serverCard(7L, "front", "back", true));
        SyncResult result = engine.sync();

        assertEquals("archiving is a delete on the server, which archives rather than removing",
                "/api/v1/cards/7", server.takeRequest().getTarget());
        assertEquals("one card's change was taken", 1, result.updated());
        assertNull("and it is no longer waiting", db.cards().findById(7L).pendingSince);
        assertTrue("the row stays, marked archived", db.cards().findById(7L).archived);
    }

    /**
     * {@code DELETE} answers 204, so the body on the path that worked is empty. Running it
     * through the reader that every other call uses would report each accepted archive as a
     * failure worth retrying, and the card would be sent forever.
     */
    @Test
    public void anArchiveAcceptedWithNoContentCountsAsSuccess() {
        cacheServerCard(7L);
        cards.archive(7L);

        server.enqueue(new MockResponse.Builder().code(204).build());
        respondToPullListing(serverCard(7L, "front", "back", true));
        SyncResult result = engine.sync();

        assertEquals("an empty 204 is the success, not a malformed answer", 1, result.updated());
        assertEquals(0, result.stalled());
    }

    @Test
    public void archivingACardTheServerNeverSawJustRemovesIt() {
        CardEntity written = cards.create(1L, "written here", "back");

        cards.archive(written.id);

        assertNull("there is nothing to tell the server, so the row goes",
                db.cards().findById(written.id));
        assertEquals("and it is not offered as a create either",
                0, db.cards().pendingCreates().size());
    }

    @Test
    public void archivingACardTheServerNeverSawDiscardsItsQueuedReviews() {
        CardEntity written = cards.create(1L, "written here", "back");
        enqueueReview(written.id);

        cards.archive(written.id);

        assertEquals(
                "those reviews name a card that will never exist on the server, so they could"
                        + " never be sent and would be counted as outstanding on every run",
                0, db.pendingReviews().size());
    }

    // ---- ordering ---------------------------------------------------------------------------

    @Test
    public void aReviewGoesUpBeforeTheArchiveOfTheCardItWasAnsweredOn()
            throws InterruptedException {
        cacheServerCard(7L);
        enqueueReview(7L);
        cards.archive(7L);

        respond(200, serverCard(7L, "front", "back", false));
        server.enqueue(new MockResponse.Builder().code(204).build());
        respondToPullListing(serverCard(7L, "front", "back", true));
        engine.sync();

        assertEquals("the review happened while the card was still in use",
                "/api/v1/study/7/review", server.takeRequest().getTarget());
        assertEquals("so it is recorded before the card is retired",
                "/api/v1/cards/7", server.takeRequest().getTarget());
    }

    // ---- fixtures ---------------------------------------------------------------------------

    private static final String CARD_GONE = """
            {"type": "about:blank", "title": "Not Found", "status": 404,
             "detail": "no card with id 7"}""";

    private static final String TOPIC = """
            {"id": 1, "name": "Operating Systems", "slug": "operating-systems",
             "createdAt": "2026-01-01T00:00:00Z"}""";

    private static MockResponse json(int code, String body) {
        return new MockResponse.Builder()
                .code(code)
                .setHeader("Content-Type", "application/json")
                .body(body)
                .build();
    }

    private void respond(int code, String body) {
        server.enqueue(json(code, body));
    }

    private void respondWithProblem(int code, String body) {
        server.enqueue(new MockResponse.Builder()
                .code(code)
                .setHeader("Content-Type", "application/problem+json")
                .body(body)
                .build());
    }

    private void respondToPullListing(String... listed) {
        respond(200, "[" + TOPIC + "]");
        respond(200, "[" + String.join(",", listed) + "]");
    }

    private static String serverCard(long id, String front, String back, boolean archived) {
        return """
                {
                  "id": %d,
                  "topicId": 1,
                  "front": "%s",
                  "back": "%s",
                  "easeFactor": 2.5,
                  "intervalDays": 6,
                  "repetitions": 2,
                  "lapses": 0,
                  "dueDate": "2026-03-17",
                  "lastReviewedAt": null,
                  "archived": %b,
                  "clientCardId": null
                }"""
                .formatted(id, front, back, archived);
    }

    /** A card as a pull would have left it: the server's id in both places, nothing unsent. */
    private void cacheServerCard(long id) {
        CardEntity card = new CardEntity();
        card.id = id;
        card.serverId = id;
        card.topicId = 1L;
        card.front = "stale";
        card.back = "back";
        card.easeFactor = 2.5d;
        card.intervalDays = 6;
        card.repetitions = 2;
        card.dueDate = TODAY;
        db.cards().upsertAll(List.of(card));
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
