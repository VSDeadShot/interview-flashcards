package dev.vsdeadshot.flashcards.data.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import dev.vsdeadshot.flashcards.data.remote.ApiException.Disposition;
import dev.vsdeadshot.flashcards.data.remote.dto.GenerateRequestDto;
import dev.vsdeadshot.flashcards.data.remote.dto.GenerateResponseDto;
import java.io.IOException;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * A real server on a loopback port rather than a stubbed interface, matching FlashcardsApiTest:
 * this exercises OkHttp, Retrofit and Moshi together, which is where the interesting mistakes are.
 *
 * <p>No Robolectric — nothing here touches the Android framework, so keeping it out makes the test
 * faster and confines the API-35 pin to the database tests.
 */
public class GenerateApiTest {

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
    public void stopServer() throws IOException {
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

    private static GenerateRequestDto request() {
        GenerateRequestDto body = new GenerateRequestDto();
        body.topicId = 2L;
        body.focus = "normalization";
        body.count = 8;
        return body;
    }

    @Test
    public void aGeneratedBatchIsParsedIntoCandidates() throws IOException {
        respond(200, "{\"candidates\":[{\"front\":\"Q1\",\"back\":\"A1\"},"
                + "{\"front\":\"Q2\",\"back\":\"A2\"}]}");

        GenerateResponseDto response = api.generate(request()).execute().body();

        assertEquals("both candidates should parse", 2, response.candidates.size());
        assertEquals("the first question should survive", "Q1", response.candidates.get(0).front);
        assertEquals("and the second answer", "A2", response.candidates.get(1).back);
    }

    @Test
    public void anEmptyBatchIsAnEmptyListRatherThanNull() throws IOException {
        respond(200, "{\"candidates\":[]}");

        GenerateResponseDto response = api.generate(request()).execute().body();

        assertTrue("an empty batch is a valid answer", response.candidates.isEmpty());
    }

    @Test
    public void theRequestCarriesTheTopicFocusAndCount() throws Exception {
        respond(200, "{\"candidates\":[]}");

        api.generate(request()).execute();
        RecordedRequest sent = server.takeRequest();

        String body = sent.getBody().utf8();
        assertEquals("it should be a POST to the generate path",
                "/api/v1/cards/generate", sent.getTarget());
        assertTrue("the topic must be sent", body.contains("\"topicId\":2"));
        assertTrue("the focus must be sent", body.contains("normalization"));
        assertTrue("the count must be sent", body.contains("\"count\":8"));
    }

    @Test
    public void theTimeoutHeaderIsConsumedLocallyAndNeverSentToTheServer() throws Exception {
        respond(200, "{\"candidates\":[]}");

        api.generate(request()).execute();
        RecordedRequest sent = server.takeRequest();

        assertNull("the read-timeout header is ours and has no meaning upstream",
                sent.getHeaders().get("X-Read-Timeout-Seconds"));
    }

    @Test
    public void aGeneratorOutageIsWorthRetrying() {
        respondWithProblem(503, "{\"status\":503,\"title\":\"Generation unavailable\","
                + "\"detail\":\"The card generator did not answer.\"}");

        ApiException thrown = assertThrows(ApiException.class,
                () -> api.generate(request()).execute());

        assertEquals("the status should survive", 503, thrown.status());
        assertEquals("an outage is temporary", Disposition.RETRY, thrown.disposition());
    }

    @Test
    public void aRefusedGenerationIsNotWorthRetrying() {
        respondWithProblem(422, "{\"status\":422,\"title\":\"Generation refused\","
                + "\"detail\":\"The card generator returned nothing usable.\"}");

        ApiException thrown = assertThrows(ApiException.class,
                () -> api.generate(request()).execute());

        assertEquals("the status should survive", 422, thrown.status());
        assertEquals("an identical retry produces the same nothing",
                Disposition.DROP, thrown.disposition());
    }

    @Test
    public void anUnknownTopicIsReportedAsNotFound() {
        respondWithProblem(404, "{\"status\":404,\"title\":\"Not found\","
                + "\"detail\":\"topic 999 was not found\"}");

        ApiException thrown = assertThrows(ApiException.class,
                () -> api.generate(request()).execute());

        assertEquals("the status should survive", 404, thrown.status());
    }
}
