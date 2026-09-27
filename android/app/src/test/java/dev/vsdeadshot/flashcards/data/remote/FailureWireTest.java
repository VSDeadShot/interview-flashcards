package dev.vsdeadshot.flashcards.data.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import dev.vsdeadshot.flashcards.data.remote.dto.LoginRequestDto;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * What OkHttp and this client's interceptors actually throw, fed through {@link Failure#of}.
 *
 * <p>{@code FailureTest} pins the rule against exceptions constructed by hand. That is only
 * worth something if those are the exceptions a real call produces, so this asks a real server.
 */
public class FailureWireTest {

    private MockWebServer server;

    @Before
    public void startServer() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @After
    public void stopServer() {
        server.close();
    }

    private static IOException failureOf(OkHttpClient client, HttpUrl url) {
        return assertThrows(IOException.class,
                () -> client.newCall(new Request.Builder().url(url).build()).execute().close());
    }

    private IOException signInFailure() {
        AuthApi api = ApiClient.auth(server.url("/api/v1/").toString());
        return assertThrows(IOException.class,
                () -> api.login(new LoginRequestDto("anything")).execute());
    }

    /**
     * A Render cold start in miniature: the connection is accepted and nothing comes back. The
     * timeout here is short only so the test is quick; the client's own is twenty seconds.
     */
    @Test
    public void aServerThatHoldsTheResponseReadsAsSlow() {
        server.enqueue(new MockResponse.Builder()
                .headersDelay(2, TimeUnit.SECONDS)
                .build());
        OkHttpClient client = new OkHttpClient.Builder()
                .readTimeout(Duration.ofMillis(250))
                .build();

        assertEquals(Failure.SLOW, Failure.of(failureOf(client, server.url("/"))));
    }

    @Test
    public void aPortNobodyIsListeningOnReadsAsOffline() {
        HttpUrl closed = server.url("/");
        server.close();

        assertEquals(Failure.OFFLINE, Failure.of(failureOf(new OkHttpClient(), closed)));
    }

    /** A router's error page is HTML, not problem+json, which is what gives it away. */
    @Test
    public void aGatewayPageInFrontOfTheBackendReadsAsSlow() {
        server.enqueue(new MockResponse.Builder()
                .code(502)
                .setHeader("Content-Type", "text/html")
                .body("<html><body>Bad Gateway</body></html>")
                .build());

        assertEquals(Failure.SLOW, Failure.of(signInFailure()));
    }

    @Test
    public void aBodiless503ReadsAsSlow() {
        server.enqueue(new MockResponse.Builder().code(503).build());

        assertEquals(Failure.SLOW, Failure.of(signInFailure()));
    }

    @Test
    public void theBackendsOwn503ReadsAsAnAnswer() {
        server.enqueue(new MockResponse.Builder()
                .code(503)
                .setHeader("Content-Type", "application/problem+json")
                .body("{\"status\":503,\"title\":\"Sign-in unavailable\","
                        + "\"detail\":\"Sign-in is not configured.\"}")
                .build());

        assertEquals(Failure.ANSWERED, Failure.of(signInFailure()));
    }
}
