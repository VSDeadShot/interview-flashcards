package dev.vsdeadshot.flashcards.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import androidx.room.Room;
import dev.vsdeadshot.flashcards.data.auth.FakeTokenStore;
import dev.vsdeadshot.flashcards.data.local.FlashcardsDatabase;
import dev.vsdeadshot.flashcards.data.local.TopicEntity;
import dev.vsdeadshot.flashcards.data.remote.ApiClient;
import dev.vsdeadshot.flashcards.data.remote.ApiException;
import java.io.IOException;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.HttpUrl;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Creating a topic: the one write to {@code topic} that does not come from a pull.
 *
 * <p>Real SQLite in memory and a real server on a loopback port, so what is checked is what a
 * device would do — that the topic the server answered with is the one the cache now holds.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class TopicRepositoryTest {

    private static final String CREATED = "{\"id\":7,\"name\":\"C++\",\"slug\":\"c\","
            + "\"createdAt\":\"2026-10-01T09:00:00Z\"}";

    private FlashcardsDatabase db;
    private MockWebServer server;
    private TopicRepository topics;

    @Before
    public void setUp() throws IOException {
        db = Room.inMemoryDatabaseBuilder(
                        RuntimeEnvironment.getApplication(), FlashcardsDatabase.class)
                .allowMainThreadQueries()
                .build();
        server = new MockWebServer();
        server.start();
        topics = repositoryAt(server.url("/api/v1/"));
    }

    @After
    public void tearDown() {
        server.close();
        db.close();
    }

    private TopicRepository repositoryAt(HttpUrl baseUrl) {
        return new TopicRepository(db,
                ApiClient.create(baseUrl.toString(), FakeTokenStore.accessOnly("token")));
    }

    private void respond(int code, String contentType, String body) {
        server.enqueue(new MockResponse.Builder()
                .code(code)
                .setHeader("Content-Type", contentType)
                .body(body)
                .build());
    }

    @Test
    public void aCreatedTopicLandsInTheCacheUnderTheServersId() throws IOException {
        respond(201, "application/json", CREATED);

        TopicEntity returned = topics.create("C++");

        TopicEntity cached = db.topics().findById(7L);
        assertNotNull("the topic should be cached at once rather than waiting for a sync", cached);
        assertEquals("under the name the server answered with", "C++", cached.name);
        assertEquals("and the slug the server derived", "c", cached.slug);
        assertEquals("the caller is handed the same topic the cache holds", 7L, returned.id);
    }

    @Test
    public void theNameIsTrimmedBeforeItIsSent() throws Exception {
        respond(201, "application/json", CREATED);

        topics.create("  C++ \n");
        RecordedRequest sent = server.takeRequest();

        assertEquals("surrounding whitespace is a typing accident, not part of the name",
                "{\"name\":\"C++\"}", sent.getBody().utf8());
    }

    @Test
    public void aRefusedCreateWritesNothing() {
        respond(409, "application/problem+json", "{\"status\":409,\"title\":\"Duplicate topic\","
                + "\"detail\":\"topic slug 'c' already exists\",\"slug\":\"c\"}");

        ApiException thrown = assertThrows(ApiException.class, () -> topics.create("C++"));

        assertEquals("the refusal reaches the caller unchanged", 409, thrown.status());
        assertTrue("a topic the server refused must not appear here",
                db.topics().findAll().isEmpty());
    }

    @Test
    public void aCreateThatNeverArrivedWritesNothing() {
        HttpUrl closed = server.url("/api/v1/");
        server.close();
        TopicRepository unreachable = repositoryAt(closed);

        assertThrows(IOException.class, () -> unreachable.create("C++"));

        assertTrue("with no answer there is no id to cache it under",
                db.topics().findAll().isEmpty());
    }
}
