package dev.vsdeadshot.flashcards.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import dev.vsdeadshot.flashcards.data.auth.FakeTokenStore;
import dev.vsdeadshot.flashcards.data.auth.TokenStore.SignedOutReason;
import dev.vsdeadshot.flashcards.data.remote.ApiClient;
import dev.vsdeadshot.flashcards.data.remote.ApiException;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Signing in and out, against a real server on a loopback port.
 *
 * <p>These go through {@code ApiClient.auth}, which is the bare client — no bearer header and no
 * authenticator. That is the property worth checking as much as any assertion here: if these
 * three routes went through the other client, a refused refresh would trigger a refresh.
 */
public class AuthRepositoryTest {

    private static final String ISSUED = """
            {
              "accessToken": "access",
              "expiresIn": 3600,
              "refreshToken": "refresh",
              "refreshExpiresIn": 2592000
            }""";

    private MockWebServer server;
    private FakeTokenStore tokens;
    private AuthRepository repository;

    @Before
    public void startServer() throws IOException {
        server = new MockWebServer();
        server.start();
        tokens = FakeTokenStore.signedOut();
        repository = new AuthRepository(
                ApiClient.auth(server.url("/api/v1/").toString()), tokens);
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

    @Test
    public void anAcceptedPassphraseIsExchangedForAPair() throws Exception {
        respond(200, ISSUED);

        repository.signIn("the passphrase");

        RecordedRequest sent = server.takeRequest();
        assertTrue(sent.getTarget().endsWith("/auth/login"));
        assertTrue("the passphrase is the whole body: " + sent.getBody().utf8(),
                sent.getBody().utf8().contains("the passphrase"));
        assertNull("sign-in is the one route that must work without a credential",
                sent.getHeaders().get("Authorization"));

        assertTrue(repository.state().signedIn());
        assertEquals("access", tokens.accessToken());
        assertEquals("refresh", tokens.refreshToken());
    }

    @Test
    public void aRefusedPassphraseStoresNothing() {
        server.enqueue(new MockResponse.Builder().code(401).build());

        ApiException refused =
                assertThrows(ApiException.class, () -> repository.signIn("wrong"));

        assertEquals(401, refused.status());
        assertFalse(repository.state().signedIn());
        assertEquals("nothing here ends a session that never started",
                SignedOutReason.NEVER, repository.state().reason());
    }

    /**
     * Half a pair is worse than none: a refresh token stored without its access token would be
     * presented on the next request and spent for nothing, and the second presentation of a
     * spent token is what the server reads as a copy in circulation.
     */
    @Test
    public void aResponseThatIsNotAPairIsAFailedSignIn() {
        respond(200, "{\"expiresIn\": 3600}");

        assertThrows(IOException.class, () -> repository.signIn("the passphrase"));

        assertNull(tokens.accessToken());
        assertNull(tokens.refreshToken());
    }

    @Test
    public void signingOutTellsTheServerAndThenClears() throws Exception {
        tokens.save("access", "refresh");
        server.enqueue(new MockResponse.Builder().code(204).build());

        repository.signOut();

        RecordedRequest sent = server.takeRequest();
        assertTrue(sent.getTarget().endsWith("/auth/logout"));
        assertTrue("the family to end is named by the refresh token: " + sent.getBody().utf8(),
                sent.getBody().utf8().contains("refresh"));

        assertFalse(repository.state().signedIn());
        assertEquals("a deliberate sign-out is not an expiry, and the screen says so differently",
                SignedOutReason.SIGNED_OUT, repository.state().reason());
    }

    /**
     * The local half happens regardless. Somebody who pressed sign out asked for this device to
     * forget the token, and a dead network is not a reason to keep it — the server-side token
     * expires within thirty days either way.
     */
    @Test
    public void signingOutClearsEvenWhenTheServerCannotBeReached() {
        tokens.save("access", "refresh");
        server.close();

        repository.signOut();

        assertFalse(repository.state().signedIn());
        assertEquals(SignedOutReason.SIGNED_OUT, repository.state().reason());
    }

    @Test
    public void signingOutWithNothingToSendSendsNothing() throws Exception {
        repository.signOut();

        assertNull("there is no family to end, so there is no request worth making",
                server.takeRequest(200, TimeUnit.MILLISECONDS));
        assertFalse(repository.state().signedIn());
    }
}
