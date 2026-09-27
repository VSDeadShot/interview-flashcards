package dev.vsdeadshot.flashcards.data.remote;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/**
 * What kind of failure a call ended in, for a screen deciding what to tell somebody.
 *
 * <p>Decided here, once, for the same reason {@link ApiException#disposition()} is: a screen
 * deciding for itself would eventually decide differently. A screen still chooses the words;
 * this only says which of four things happened.
 *
 * <p>This is not the sync's question. The outbox asks whether to retry, which
 * {@code disposition()} answers, and it does not care why the server was out of reach.
 */
public enum Failure {

    /**
     * The server was reached and did not answer in time, or the platform's router answered for
     * it. On Render this is usually an idle instance waking, which takes about two minutes —
     * longer than this client waits. The device's connection is fine.
     */
    SLOW,

    /** The server could not be reached at all: no name lookup, or no connection. */
    OFFLINE,

    /** The exchange started and failed some other way, such as an answer with nothing in it. */
    BROKEN,

    /** The backend itself answered with an error, and its status says which. */
    ANSWERED;

    public static Failure of(IOException failure) {
        if (failure instanceof ApiException api) {
            return fromGateway(api) ? SLOW : ANSWERED;
        }
        if (failure instanceof SocketTimeoutException) {
            // Checked before the connection failures, and deliberately not treated as one. With
            // no network the name lookup fails first, so a timeout almost always means something
            // was reached and went quiet — which is exactly what a waking instance does.
            return SLOW;
        }
        if (failure instanceof UnknownHostException
                || failure instanceof ConnectException
                || failure instanceof NoRouteToHostException) {
            return OFFLINE;
        }
        return BROKEN;
    }

    /**
     * The backend never sends a {@code 502} or {@code 504}, and every {@code 503} it sends
     * carries a problem body — the backend's own tests pin both of its {@code 503}s. So these
     * came from the router in front of it, not from anything the backend decided.
     */
    private static boolean fromGateway(ApiException api) {
        int status = api.status();
        return status == 502 || status == 504 || (status == 503 && !api.hasProblem());
    }
}
