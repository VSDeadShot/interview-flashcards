package dev.vsdeadshot.flashcards.data.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import dev.vsdeadshot.flashcards.data.remote.dto.LoginRequestDto;
import dev.vsdeadshot.flashcards.data.remote.dto.RefreshRequestDto;
import dev.vsdeadshot.flashcards.data.remote.dto.TokenResponseDto;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import retrofit2.Response;

/**
 * How long signing in is allowed to wait, and that nothing else was given the same allowance.
 *
 * <p>A Render instance idle long enough to be put to sleep takes about two minutes to answer its
 * first request, and the client's default read timeout is twenty seconds. Sign-in is the one call
 * a person makes right after opening the app, so it is the one that meets a sleeping server.
 */
public class SignInTimeoutTest {

    private static final String ISSUED = """
            {
              "accessToken": "access-token",
              "expiresIn": 3600,
              "refreshToken": "refresh-token",
              "refreshExpiresIn": 2592000
            }""";

    private MockWebServer server;
    private AuthApi api;

    @Before
    public void startServer() throws IOException {
        server = new MockWebServer();
        server.start();
        api = ApiClient.auth(server.url("/api/v1/").toString());
    }

    @After
    public void stopServer() {
        server.close();
    }

    /**
     * A server waking from idle, in miniature: the connection is accepted and nothing comes back
     * for longer than the client's default read timeout. The real wait is about two minutes;
     * twenty-five seconds is enough to prove the default no longer applies without making every
     * run of the suite wait out a real cold start. It is the only slow test in the suite, on
     * purpose — nothing else here proves the configured client end to end.
     */
    @Test
    public void signingInWaitsPastTheClientsDefaultReadTimeout() throws Exception {
        server.enqueue(new MockResponse.Builder()
                .headersDelay(25, TimeUnit.SECONDS)
                .code(200)
                .setHeader("Content-Type", "application/json")
                .body(ISSUED)
                .build());

        Response<TokenResponseDto> answered;
        try {
            answered = api.login(new LoginRequestDto("the passphrase")).execute();
        } catch (IOException gaveUp) {
            fail("sign-in gave up before a waking server answered: " + Failure.of(gaveUp));
            return;
        }

        assertEquals(200, answered.code());
        assertEquals("the pair that took a cold start to arrive is the one read back",
                "access-token", answered.body().accessToken);

        RecordedRequest sent = server.takeRequest();
        assertNull("the timeout header is this client's, and never reaches the server",
                sent.getHeaders().get(TimeoutInterceptor.HEADER));
    }

    /**
     * Only sign-in waits that long. Renewal runs inside a background sync, where a slow answer
     * already costs nothing — the tokens are kept and the next sync tries again — and logout is
     * swallowed on failure anyway. Giving either the long wait would only delay finding out.
     */
    @Test
    public void refreshAndLogoutKeepTheDefaultTimeout() {
        assertNull("a renewal keeps the client's default",
                api.refresh(new RefreshRequestDto("refresh")).request()
                        .header(TimeoutInterceptor.HEADER));
        assertNull("so does a logout",
                api.logout(new RefreshRequestDto("refresh")).request()
                        .header(TimeoutInterceptor.HEADER));
    }
}
