package dev.vsdeadshot.flashcards.data.remote;

import static org.junit.Assert.assertEquals;

import dev.vsdeadshot.flashcards.data.remote.dto.ProblemDetail;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import org.junit.Test;

/**
 * What kind of failure a call ended in, decided once so every screen words it the same way.
 *
 * <p>No Robolectric and no server: this is a function of the exception alone. What OkHttp
 * actually throws in each situation is {@code FailureWireTest}'s job; this pins the rule.
 */
public class FailureTest {

    private static ProblemDetail problem(int status, String title) {
        ProblemDetail problem = new ProblemDetail();
        problem.status = status;
        problem.title = title;
        return problem;
    }

    /**
     * The case this exists for. A Render instance waking from idle accepts the connection and
     * then says nothing for about two minutes, so the read times out on a device that is
     * perfectly online. Reporting that as "needs a connection" sends somebody to check a Wi-Fi
     * that is working.
     */
    @Test
    public void aTimeoutIsASlowServerNotAMissingConnection() {
        assertEquals(Failure.SLOW, Failure.of(new SocketTimeoutException("timeout")));
    }

    @Test
    public void aHostThatCannotBeResolvedIsNoConnection() {
        // What a device with no network actually produces: the name lookup fails first.
        assertEquals(Failure.OFFLINE, Failure.of(new UnknownHostException("example.invalid")));
    }

    @Test
    public void aConnectionThatCannotBeMadeIsNoConnection() {
        assertEquals(Failure.OFFLINE, Failure.of(new ConnectException("Failed to connect")));
        assertEquals(Failure.OFFLINE, Failure.of(new NoRouteToHostException("No route to host")));
    }

    /**
     * {@code AuthRepository.signIn} throws exactly this when the server answers 200 with no
     * token pair. The device reached the server, so calling it offline would be wrong.
     */
    @Test
    public void anExchangeThatFailedSomeOtherWayIsBrokenNotOffline() {
        assertEquals(Failure.BROKEN, Failure.of(new IOException("Sign-in returned no token pair")));
    }

    @Test
    public void aGatewayErrorIsTheServerWaking() {
        // The backend never sends either of these; only the platform's router in front of it.
        assertEquals(Failure.SLOW, Failure.of(ApiException.from(502, null)));
        assertEquals(Failure.SLOW, Failure.of(ApiException.from(504, null)));
    }

    /**
     * Every 503 the backend sends carries a problem body, which the backend's own tests pin. So
     * a 503 without one is not the backend: it is the router answering for an instance that is
     * not up yet.
     */
    @Test
    public void aBodiless503IsTheRouterNotTheBackend() {
        assertEquals(Failure.SLOW, Failure.of(ApiException.from(503, null)));
    }

    @Test
    public void a503WithAProblemBodyIsTheBackendAnswering() {
        assertEquals(Failure.ANSWERED,
                Failure.of(ApiException.from(503, problem(503, "Sign-in unavailable"))));
    }

    /**
     * A bodyless 500 is the backend's deliberate answer for a Gemini key or model it cannot
     * use. It must stay an answer, or generation's "not set up correctly" would turn into
     * "try again shortly" for a fault that waiting will never fix.
     */
    @Test
    public void everyOtherErrorResponseIsAnAnswer() {
        assertEquals(Failure.ANSWERED, Failure.of(ApiException.from(500, null)));
        assertEquals(Failure.ANSWERED, Failure.of(ApiException.from(404, null)));
        assertEquals(Failure.ANSWERED, Failure.of(ApiException.from(401, null)));
        assertEquals(Failure.ANSWERED,
                Failure.of(ApiException.from(429, problem(429, "Generation limit reached"))));
    }
}
