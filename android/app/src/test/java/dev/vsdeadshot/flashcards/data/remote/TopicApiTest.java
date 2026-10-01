package dev.vsdeadshot.flashcards.data.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import dev.vsdeadshot.flashcards.data.auth.FakeTokenStore;
import dev.vsdeadshot.flashcards.data.remote.dto.CreateTopicRequestDto;
import dev.vsdeadshot.flashcards.data.remote.dto.TopicDto;
import java.io.IOException;
import java.time.Instant;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * {@code POST /topics}, which existed on {@link FlashcardsApi} long before anything called it.
 *
 * <p>A real server on a loopback port, matching GenerateApiTest and for its reason: OkHttp,
 * Retrofit and Moshi together are where the interesting mistakes are. No Robolectric.
 */
public class TopicApiTest {

    private static final String TOKEN = "test-access-token";

    private MockWebServer server;
    private FlashcardsApi api;

    @Before
    public void startServer() throws IOException {
        server = new MockWebServer();
        server.start();
        api = ApiClient.create(server.url("/api/v1/").toString(),
                FakeTokenStore.accessOnly(TOKEN));
    }

    @After
    public void stopServer() throws IOException {
        server.close();
    }

    private void respond(int code, String contentType, String body) {
        server.enqueue(new MockResponse.Builder()
                .code(code)
                .setHeader("Content-Type", contentType)
                .body(body)
                .build());
    }

    @Test
    public void theRequestIsAPostCarryingOnlyTheName() throws Exception {
        respond(201, "application/json", "{\"id\":7,\"name\":\"C++\",\"slug\":\"c\","
                + "\"createdAt\":\"2026-10-01T09:00:00Z\"}");

        api.createTopic(new CreateTopicRequestDto("C++")).execute();
        RecordedRequest sent = server.takeRequest();

        assertEquals("it should be a POST", "POST", sent.getMethod());
        assertEquals("to the topics path", "/api/v1/topics", sent.getTarget());
        assertEquals("the body is the name and nothing else -- the slug is the server's to derive",
                "{\"name\":\"C++\"}", sent.getBody().utf8());
    }

    @Test
    public void aCreatedTopicIsParsedFromTheBody() throws IOException {
        respond(201, "application/json", "{\"id\":7,\"name\":\"C++\",\"slug\":\"c\","
                + "\"createdAt\":\"2026-10-01T09:00:00Z\"}");

        TopicDto created = api.createTopic(new CreateTopicRequestDto("C++")).execute().body();

        assertEquals("the server's id is what the cache keys on", 7L, created.id);
        assertEquals("the name should survive", "C++", created.name);
        assertEquals("the derived slug should survive", "c", created.slug);
        assertEquals("the timestamp should parse",
                Instant.parse("2026-10-01T09:00:00Z"), created.createdAt);
    }

    @Test
    public void aDuplicateNameIsReportedAsAConflict() {
        respond(409, "application/problem+json", "{\"status\":409,\"title\":\"Duplicate topic\","
                + "\"detail\":\"topic slug 'operating-systems' already exists\","
                + "\"slug\":\"operating-systems\"}");

        ApiException thrown = assertThrows(ApiException.class,
                () -> api.createTopic(new CreateTopicRequestDto("Operating Systems")).execute());

        assertEquals("the conflict should reach the caller as a 409", 409, thrown.status());
    }

    @Test
    public void aNameWithNothingSluggableInItIsReportedAsInvalid() {
        respond(400, "application/problem+json", "{\"status\":400,\"title\":\"Validation failed\","
                + "\"detail\":\"name must contain at least one letter or digit, was '!!!'\"}");

        ApiException thrown = assertThrows(ApiException.class,
                () -> api.createTopic(new CreateTopicRequestDto("!!!")).execute());

        assertEquals("the refusal should reach the caller as a 400", 400, thrown.status());
    }
}
