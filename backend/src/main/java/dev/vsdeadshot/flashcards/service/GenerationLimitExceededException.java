package dev.vsdeadshot.flashcards.service;

/**
 * The caller has used up the generations allowed for one day.
 *
 * <p>Distinct from {@code GenerationUnavailableException}, which also invites a later retry: that
 * one is the upstream being unwell and could clear in seconds, this one clears at a knowable
 * time and not before. A client told only "try again shortly" would poll a limit that is not
 * going to move, which is the same mistake as advising a retry for a rejected credential.
 *
 * @param retryAfterSeconds how long until the cap resets, so the answer can say when rather than
 *                          leaving a client to guess or poll
 */
public class GenerationLimitExceededException extends RuntimeException {

    private final int limit;
    private final long retryAfterSeconds;

    public GenerationLimitExceededException(int limit, long retryAfterSeconds) {
        super("the limit of " + limit + " generations per day has been reached");
        this.limit = limit;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int getLimit() {
        return limit;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
