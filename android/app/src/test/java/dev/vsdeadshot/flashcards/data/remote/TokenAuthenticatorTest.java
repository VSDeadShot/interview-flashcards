package dev.vsdeadshot.flashcards.data.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import dev.vsdeadshot.flashcards.data.auth.FakeTokenStore;
import dev.vsdeadshot.flashcards.data.auth.TokenStore.SignedOutReason;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Renewal, against a real server on a loopback port. No Robolectric — nothing here touches the
 * Android framework, which is what {@code TokenStore} being an interface buys.
 *
 * <p>The claim these are mostly here to check is one about OkHttp's own layering rather than
 * about this code: an {@code Authenticator} runs inside {@code RetryAndFollowUpInterceptor},
 * <em>below</em> the application interceptors, so a {@code 401} that is recovered from never
 * reaches {@link ProblemInterceptor} and no caller above it sees anything but the answer it
 * asked for. The whole design rests on that being true, and it was worth checking rather than
 * reading.
 */
public class TokenAuthenticatorTest {

    private static final String OLD_ACCESS = "old-access-token";
    private static final String OLD_REFRESH = "old-refresh-token";
    private static final String NEW_ACCESS = "new-access-token";
    private static final String NEW_REFRESH = "new-refresh-token";

    private static final String ISSUED = """
            {
              "accessToken": "new-access-token",
              "expiresIn": 3600,
              "refreshToken": "new-refresh-token",
              "refreshExpiresIn": 2592000
            }""";

    private MockWebServer server;
    private FakeTokenStore tokens;
    private FlashcardsApi api;

    @Before
    public void startServer() throws IOException {
        server = new MockWebServer();
        server.start();
        tokens = FakeTokenStore.signedIn(OLD_ACCESS, OLD_REFRESH);
        api = ApiClient.create(server.url("/api/v1/").toString(), tokens);
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

    /** What the backend actually sends: the filter rejects before any handler runs. */
    private void respondUnauthorized() {
        server.enqueue(new MockResponse.Builder().code(401).build());
    }

    @Test
    public void anExpiredAccessTokenIsRenewedAndTheRequestIsRetried() throws Exception {
        respondUnauthorized();
        respond(200, ISSUED);
        respond(200, "[]");

        assertNotNull("the caller gets its answer, not the 401 on the way to it",
                api.topics().execute().body());

        assertEquals("the original, the refresh, and the retry", 3, server.getRequestCount());

        RecordedRequest first = server.takeRequest();
        assertEquals("Bearer " + OLD_ACCESS, first.getHeaders().get("Authorization"));

        RecordedRequest refresh = server.takeRequest();
        assertTrue("the refresh goes to the one route that needs no credential",
                refresh.getTarget().endsWith("/auth/refresh"));
        assertTrue("it presents the refresh token it holds: " + refresh.getBody().utf8(),
                refresh.getBody().utf8().contains(OLD_REFRESH));
        assertNull("and carries no bearer of its own, since the one it had was refused",
                refresh.getHeaders().get("Authorization"));

        RecordedRequest retry = server.takeRequest();
        assertEquals("the retry carries the new token, which only the authenticator can set — "
                        + "a follow-up does not pass back through the application interceptors",
                "Bearer " + NEW_ACCESS, retry.getHeaders().get("Authorization"));
    }

    /**
     * The claim the whole design rests on. {@code ProblemInterceptor} is an application
     * interceptor and turns every non-2xx into an exception; if it saw the intermediate 401,
     * adding renewal would have meant touching all nine methods of {@link FlashcardsApi} and the
     * sync engine that drives them.
     */
    @Test
    public void aRecoveredRejectionIsInvisibleToTheProblemInterceptor() throws Exception {
        respondUnauthorized();
        respond(200, ISSUED);
        respond(200, "[]");

        // No exception. That is the assertion — ProblemInterceptor would have thrown on the 401.
        assertEquals(200, api.topics().execute().code());
    }

    @Test
    public void bothHalvesOfTheNewPairAreStored() throws Exception {
        respondUnauthorized();
        respond(200, ISSUED);
        respond(200, "[]");

        api.topics().execute();

        assertEquals(NEW_ACCESS, tokens.accessToken());
        assertEquals("the server rotates the refresh token on every exchange, so keeping the old"
                        + " one would leave this device holding a value the server treats as a copy",
                NEW_REFRESH, tokens.refreshToken());
    }

    @Test
    public void aRefusedRefreshTokenEndsTheSessionAndSaysWhy() {
        respondUnauthorized();
        // Expired, revoked by a logout elsewhere, or presented twice and treated as stolen. The
        // server does not distinguish them, on purpose, and a client would not act differently.
        respondUnauthorized();

        ApiException failure = assertThrows(ApiException.class, () -> api.topics().execute());

        assertEquals("the original 401 is what the caller finally sees", 401, failure.status());
        assertEquals(ApiException.Disposition.STOP, failure.disposition());
        assertFalse("the tokens are gone", tokens.state().signedIn());
        assertEquals("and the reason is the only thing that can reach a screen — a periodic"
                        + " worker's result carries nothing back",
                SignedOutReason.SESSION_EXPIRED, tokens.state().reason());
    }

    /**
     * A refresh that failed for a passing reason must not sign anybody out. The distinction
     * matters: sending somebody to a sign-in screen over a 503 asks them to type a passphrase to
     * fix a server they do not control.
     */
    @Test
    public void aServerFaultDuringRenewalLeavesTheTokensAlone() {
        respondUnauthorized();
        server.enqueue(new MockResponse.Builder()
                .code(503)
                .setHeader("Content-Type", "application/problem+json")
                .body("{\"status\":503,\"title\":\"Service Unavailable\"}")
                .build());

        assertThrows(ApiException.class, () -> api.topics().execute());

        assertTrue("nothing about a 503 says the refresh token is finished",
                tokens.state().signedIn());
        assertEquals(OLD_REFRESH, tokens.refreshToken());
    }

    @Test
    public void withNoRefreshTokenNothingIsAttempted() throws Exception {
        FakeTokenStore anonymous = FakeTokenStore.signedOut();
        FlashcardsApi client =
                ApiClient.create(server.url("/api/v1/").toString(), anonymous);
        respondUnauthorized();

        assertThrows(ApiException.class, () -> client.topics().execute());

        server.takeRequest();
        assertEquals("there is nothing to exchange, so no request is worth making",
                1, server.getRequestCount());
        assertEquals("and a sign-out somebody chose must not be relabelled as an expiry",
                SignedOutReason.NEVER, anonymous.state().reason());
        assertNull("nothing further was sent",
                server.takeRequest(200, TimeUnit.MILLISECONDS));
    }

    /**
     * A server that rejects a token it has just issued would otherwise be an unbounded loop:
     * 401, refresh, 401, refresh. The retry is capped at one, so the second rejection is
     * reported rather than answered.
     */
    @Test
    public void aTokenRejectedImmediatelyAfterRenewalIsNotChasedForever() {
        respondUnauthorized();
        respond(200, ISSUED);
        respondUnauthorized();

        ApiException failure = assertThrows(ApiException.class, () -> api.topics().execute());

        assertEquals(401, failure.status());
        assertEquals("one renewal per call, then the answer is reported as it stands",
                3, server.getRequestCount());
        assertTrue("the new pair was stored, and is not thrown away because one request failed",
                tokens.state().signedIn());
    }

    @Test
    public void aRenewalThatReturnsNoPairIsNotStoredInPart() {
        respondUnauthorized();
        // A 2xx that is not a token pair. Storing the half of it that parsed would leave a spent
        // refresh token on the device, and presenting that is what revokes a whole family.
        respond(200, "{\"expiresIn\": 3600}");

        assertThrows(ApiException.class, () -> api.topics().execute());

        assertEquals("the pair on the device is untouched", OLD_ACCESS, tokens.accessToken());
        assertEquals(OLD_REFRESH, tokens.refreshToken());
        assertEquals("nothing was saved", 0, tokens.saves());
    }
}
