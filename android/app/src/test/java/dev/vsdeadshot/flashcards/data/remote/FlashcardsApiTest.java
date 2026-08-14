package dev.vsdeadshot.flashcards.data.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.squareup.moshi.JsonDataException;
import dev.vsdeadshot.flashcards.data.remote.ApiException.Disposition;
import dev.vsdeadshot.flashcards.data.remote.dto.CardDto;
import dev.vsdeadshot.flashcards.data.remote.dto.ReviewRequestDto;
import dev.vsdeadshot.flashcards.data.remote.dto.StatsDto;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The remote layer against a real HTTP server on a loopback port, so these exercise OkHttp,
 * Retrofit and Moshi rather than a stub standing in for them — the same reason the database
 * tests run real SQLite and the backend runs real Postgres.
 *
 * <p>No Robolectric: nothing here touches the Android framework, and keeping it out means these
 * are not pinned to whichever API level Robolectric happens to support.
 *
 * <p>What a mock server cannot prove is that this client and Jackson agree on how an instant is
 * written. The bodies below are copied from the payloads in {@code docs/api-contract.md}, which
 * narrows it; only a round trip against the running backend settles it.
 */
public class FlashcardsApiTest {

    private static final String KEY = "test-key";

    private MockWebServer server;
    private FlashcardsApi api;

    @Before
    public void startServer() throws IOException {
        server = new MockWebServer();
        server.start();
        api = ApiClient.create(server.url("/api/v1/").toString(), KEY);
    }

    @After
    public void stopServer() {
        server.close();
    }

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

    private static final String CARD_JSON = """
            {
              "id": 12,
              "topicId": 3,
              "front": "What is a deadlock?",
              "back": "Four Coffman conditions",
              "easeFactor": 2.5,
              "intervalDays": 6,
              "repetitions": 2,
              "lapses": 0,
              "dueDate": "2026-08-03",
              "lastReviewedAt": "2026-07-28T19:40:00Z",
              "archived": false
            }""";

    @Test
    public void everyRequestCarriesTheApiKey() throws Exception {
        respond(200, "[]");

        api.topics().execute();

        RecordedRequest sent = server.takeRequest();
        assertEquals("without this header the server answers 401 and nothing else works",
                KEY, sent.getHeaders().get("X-API-Key"));
    }

    @Test
    public void aBlankKeyIsRefusedBeforeAnythingIsSent() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> ApiClient.create(server.url("/").toString(), ""));

        assertTrue("the message has to name the property, not the symptom",
                refused.getMessage().contains("local.properties"));
    }

    @Test
    public void aCardArrivesWithItsDatesTypedRatherThanAsStrings() throws Exception {
        respond(200, "[" + CARD_JSON + "]");

        CardDto card = api.cards(null, true).execute().body().get(0);

        assertEquals(12L, card.id);
        assertEquals(LocalDate.of(2026, 8, 3), card.dueDate);
        assertEquals(Instant.parse("2026-07-28T19:40:00Z"), card.lastReviewedAt);
        assertEquals(2.5d, card.easeFactor, 1e-9);
        assertEquals(6, card.intervalDays);
    }

    @Test
    public void aCardThatWasNeverReviewedKeepsANullTimestamp() throws Exception {
        respond(200, "[" + CARD_JSON.replace("\"2026-07-28T19:40:00Z\"", "null") + "]");

        assertNull("null means never reviewed, and must not become the epoch",
                api.cards(null, true).execute().body().get(0).lastReviewedAt);
    }

    @Test
    public void aNullWhereTheScheduleExpectsANumberIsRefusedRatherThanReadAsZero() {
        respond(200, "[" + CARD_JSON.replace("2.5", "null") + "]");

        // The reason the scheduling fields are primitives. Read as 0.0 this would reschedule
        // every card from an ease factor no card can have, and nothing would report it.
        assertThrows(JsonDataException.class, () -> api.cards(null, true).execute());
    }

    @Test
    public void aPullAsksForArchivedCardsAndSaysSoInTheQuery() throws Exception {
        respond(200, "[]");

        api.cards(null, true).execute();

        assertEquals("/api/v1/cards?includeArchived=true", server.takeRequest().getTarget());
    }

    @Test
    public void anAbsentQueryParameterIsOmittedRatherThanSentEmpty() throws Exception {
        respond(200, "[]");

        api.queue(null).execute();

        assertEquals("the server applies its own default when the parameter is absent",
                "/api/v1/study/queue", server.takeRequest().getTarget());
    }

    @Test
    public void aReviewSendsTheInstantInTheFormTheServerParses() throws Exception {
        respond(200, CARD_JSON);
        UUID key = UUID.fromString("6f1e7d3a-0b2c-4d5e-8f90-1a2b3c4d5e6f");

        api.review(12L, new ReviewRequestDto(4, Instant.parse("2026-03-17T09:00:00Z"), key))
                .execute();

        String body = server.takeRequest().getBody().utf8();
        assertTrue("an offline review is credited to the day it names, so the format matters: "
                        + body,
                body.contains("\"reviewedAt\":\"2026-03-17T09:00:00Z\""));
        assertTrue("the key is what makes resending it safe: " + body,
                body.contains("\"clientReviewId\":\"" + key + "\""));
        assertTrue(body.contains("\"confidence\":4"));
    }

    @Test
    public void anOptionalFieldThisClientDoesNotSetIsOmittedEntirely() throws Exception {
        respond(200, CARD_JSON);

        api.review(12L, new ReviewRequestDto(4, null, null)).execute();

        String body = server.takeRequest().getBody().utf8();
        assertEquals("absent is the contract's word for use-your-own-clock; an explicit null "
                        + "would be a value the server has to interpret: " + body,
                "{\"confidence\":4}", body);
    }

    @Test
    public void statsArriveWholeIncludingTheTopicBreakdown() throws Exception {
        respond(200, """
                {
                  "totalCards": 140,
                  "dueToday": 12,
                  "reviewedToday": 8,
                  "currentStreakDays": 5,
                  "byTopic": [{"topicId": 3, "name": "Operating Systems", "total": 40, "due": 4}]
                }""");

        StatsDto stats = api.stats().execute().body();

        assertEquals(140L, stats.totalCards);
        assertEquals(5, stats.currentStreakDays);
        assertEquals(1, stats.byTopic.size());
        assertEquals("Operating Systems", stats.byTopic.get(0).name);
        assertEquals(4L, stats.byTopic.get(0).due);
    }

    @Test
    public void aRejectedRequestBecomesAnExceptionCarryingTheExplanation() {
        respondWithProblem(400,
                "{\"status\":400,\"title\":\"Validation failed\","
                        + "\"detail\":\"confidence must be between 1 and 5\"}");

        ApiException failure = assertThrows(ApiException.class, () -> api.stats().execute());

        assertEquals(400, failure.status());
        assertEquals("confidence must be between 1 and 5", failure.detail());
        assertEquals("a body the server will never accept is not worth queueing forever",
                Disposition.DROP, failure.disposition());
    }

    @Test
    public void aRacedIdempotencyKeyIsWorthSendingAgain() {
        respondWithProblem(409,
                "{\"status\":409,\"title\":\"Conflict\",\"detail\":\"in flight\","
                        + "\"retryable\":true}");

        ApiException failure = assertThrows(ApiException.class, () -> api.stats().execute());

        assertEquals("the loser of a race gets the original result once the winner lands",
                Disposition.RETRY, failure.disposition());
    }

    @Test
    public void aReusedIdempotencyKeyIsNot() {
        respondWithProblem(409,
                "{\"status\":409,\"title\":\"Conflict\",\"detail\":\"key reused\","
                        + "\"retryable\":false}");

        ApiException failure = assertThrows(ApiException.class, () -> api.stats().execute());

        assertEquals("no retry can make one key mean two different reviews",
                Disposition.DROP, failure.disposition());
    }

    @Test
    public void aConflictSayingNothingAboutRetryingIsTreatedAsPermanent() {
        respondWithProblem(409,
                "{\"status\":409,\"title\":\"Conflict\",\"detail\":\"duplicate\","
                        + "\"slug\":\"os\"}");

        ApiException failure = assertThrows(ApiException.class, () -> api.stats().execute());

        assertEquals("a retry loop on a conflict is the worse of the two failures",
                Disposition.DROP, failure.disposition());
    }

    @Test
    public void anUnknownCardIsDroppedRatherThanQueuedForever() {
        respondWithProblem(404,
                "{\"status\":404,\"title\":\"Not found\",\"detail\":\"card 12 not found\"}");

        ApiException failure = assertThrows(ApiException.class, () -> api.stats().execute());

        assertEquals("a card archived on another device will never accept this review",
                Disposition.DROP, failure.disposition());
    }

    @Test
    public void aRejectedKeyStopsTheWholeSyncRatherThanOneEntry() {
        // Exactly what the running backend answers, checked against it: the filter rejects the
        // request before any handler runs, so there is no problem+json here — no body at all,
        // and no content type. The status is the whole message.
        server.enqueue(new MockResponse.Builder().code(401).build());

        ApiException failure = assertThrows(ApiException.class, () -> api.stats().execute());

        assertEquals(401, failure.status());
        assertNull("an empty body is not a parse failure worth reporting", failure.detail());
        assertEquals("draining the rest would log one identical failure per queued row",
                Disposition.STOP, failure.disposition());
    }

    @Test
    public void aServerFaultIsWorthRetrying() {
        respondWithProblem(500, "{\"status\":500,\"title\":\"Internal Server Error\"}");

        ApiException failure = assertThrows(ApiException.class, () -> api.stats().execute());

        assertEquals(Disposition.RETRY, failure.disposition());
    }

    @Test
    public void anErrorBodyThatIsNotProblemJsonStillDecidesTheOutcome() {
        server.enqueue(new MockResponse.Builder()
                .code(502)
                .setHeader("Content-Type", "text/html")
                .body("<html><body>Bad Gateway</body></html>")
                .build());

        ApiException failure = assertThrows(ApiException.class, () -> api.stats().execute());

        assertEquals(502, failure.status());
        assertNull("nothing to explain, but the status is what the decision rests on",
                failure.detail());
        assertEquals(Disposition.RETRY, failure.disposition());
    }

    @Test
    public void aDeadNetworkIsRetriedLikeAnyOtherTransientFailure() {
        assertEquals("the case the whole outbox exists for", Disposition.RETRY,
                ApiException.dispositionOf(new IOException("no route to host")));
    }
}
