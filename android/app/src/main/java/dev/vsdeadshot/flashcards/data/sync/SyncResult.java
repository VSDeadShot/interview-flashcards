package dev.vsdeadshot.flashcards.data.sync;

/**
 * What one run of {@link SyncEngine#sync()} did.
 *
 * <p>A record because nothing reflects over it. That is the distinction from the wire DTOs,
 * which are plain classes: Moshi's record support needs {@code java.lang.Record} reflection
 * Android's runtime does not provide, whereas records themselves desugar to ordinary classes
 * and run anywhere.
 *
 * @param outcome how far the run got
 * @param created cards written on this device that the server has now made
 * @param updated cards whose local change the server has now taken — an edit or an archive,
 *     counted together because they are one loop answering one question: is this row still
 *     different from the server's?
 * @param pushed reviews the server accepted and that are now gone from the outbox
 * @param dropped reviews the server will never accept, discarded so they stop blocking the card
 *     behind them
 * @param stalled work still outstanding because sending it failed in a way worth retrying
 * @param blocked work still outstanding that retrying cannot fix — a card the server refuses for
 *     a reason that will repeat, and any review of it. Counted so it is visible, and deliberately
 *     kept out of {@link #hasWorkLeft()}
 * @param topicsWritten topics the pull wrote to the cache
 * @param cardsWritten cards the pull wrote — fewer than the server listed when a card was
 *     skipped for having a review still queued
 */
public record SyncResult(
        Outcome outcome,
        int created,
        int updated,
        int pushed,
        int dropped,
        int stalled,
        int blocked,
        int topicsWritten,
        int cardsWritten) {

    public enum Outcome {

        /** The outbox was drained as far as it could be and the pull completed. */
        OK,

        /**
         * The key was rejected. Every request would be rejected the same way, so nothing after
         * the first was attempted — retrying on a timer would just repeat that.
         */
        STOPPED,

        /**
         * The pull did not complete. A dead network and a server that refused the request are
         * one outcome here on purpose: the answer to both is to try again later, and only
         * {@link #STOPPED} calls for something else.
         */
        FAILED
    }

    /**
     * Whether another attempt is worth making. True after a run that pushed nothing because the
     * network was down, and equally after one that pushed nine reviews and stalled on the
     * tenth — a caller scheduling the next attempt cares about the queue, not the tally.
     *
     * <p><strong>{@code blocked} is deliberately not part of this.</strong> Work the server
     * refuses for a reason that will repeat is outstanding, but no backoff timer will change the
     * answer — only a person editing the card will. Counting it here would have the worker retry
     * for as long as the card sits there, spending the battery to be told the same thing.
     */
    public boolean hasWorkLeft() {
        return outcome != Outcome.OK || stalled > 0;
    }
}
