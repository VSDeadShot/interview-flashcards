package dev.vsdeadshot.flashcards.data.remote;

import dev.vsdeadshot.flashcards.data.remote.dto.ProblemDetail;
import java.io.IOException;

/**
 * A request the server answered with an error.
 *
 * <p>Extends {@link IOException} so a caller can catch one type around
 * {@link retrofit2.Call#execute()} and have both a dead network and a rejected request in hand.
 * They are the same event to an outbox — a review that has not landed yet — and differ only in
 * what should happen next, which is what {@link #disposition()} answers.
 */
public final class ApiException extends IOException {

    /**
     * What the sync should do about a failure. Three answers, because there are three: send it
     * again later, give up on this entry, or stop entirely.
     *
     * <p>This lives with the exception rather than in the sync engine so that the rule is
     * stated once. A caller deciding for itself would eventually decide differently.
     */
    public enum Disposition {

        /** Transient. The same request, unchanged, is expected to succeed later. */
        RETRY,

        /**
         * Permanent. The server will never accept this request, so keeping it queued blocks
         * everything behind it forever — a review for a card archived on another device is the
         * realistic case. The row is deleted rather than marked dead: it is also what keeps
         * its card out of every pull, so a row left behind freezes that card permanently. The
         * reason is logged and counted by the sync instead.
         */
        DROP,

        /**
         * Nothing will work. A rejected key applies to every queued entry equally, so draining
         * the rest would produce one identical failure per row and no progress.
         */
        STOP
    }

    private final int status;
    private final String detail;
    private final Boolean retryable;
    private final boolean problem;

    private ApiException(int status, String detail, Boolean retryable, boolean problem) {
        super(status + (detail == null ? "" : " " + detail));
        this.status = status;
        this.detail = detail;
        this.retryable = retryable;
        this.problem = problem;
    }

    static ApiException from(int status, ProblemDetail problem) {
        return problem == null
                ? new ApiException(status, null, null, false)
                : new ApiException(status, problem.detail, problem.retryable, true);
    }

    public int status() {
        return status;
    }

    /**
     * Whether the response carried a problem body. Every error the backend itself sends does,
     * except its deliberately bodyless {@code 401} and {@code 500}, so for a {@code 503} this
     * is what tells the backend speaking from the platform's router answering on its behalf.
     */
    public boolean hasProblem() {
        return problem;
    }

    /** The server's explanation, or null if it did not give a parseable one. */
    public String detail() {
        return detail;
    }

    public Disposition disposition() {
        if (status == 401 || status == 403) {
            return Disposition.STOP;
        }
        if (status == 409) {
            // The one place the server distinguishes the two conflicts it can report: a request
            // that raced another carrying the same idempotency key, which the loser should send
            // again, from a key reused for a different payload, which no retry can fix. Absent
            // — a duplicate topic slug says nothing about retrying — the conflict is treated as
            // permanent, because a retry loop on a conflict is the worse failure of the two.
            return Boolean.TRUE.equals(retryable) ? Disposition.RETRY : Disposition.DROP;
        }
        if (status == 408 || status == 429 || status >= 500) {
            return Disposition.RETRY;
        }
        return status >= 400 ? Disposition.DROP : Disposition.RETRY;
    }

    /**
     * The disposition of any failure a call can throw, so a caller does not have to test the
     * type. A plain {@link IOException} is the network being unavailable, which is the case the
     * whole outbox exists for and is always worth retrying.
     */
    public static Disposition dispositionOf(IOException failure) {
        return failure instanceof ApiException api ? api.disposition() : Disposition.RETRY;
    }
}
