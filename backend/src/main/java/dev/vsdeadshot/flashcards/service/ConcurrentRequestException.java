package dev.vsdeadshot.flashcards.service;

/**
 * Two requests carrying the same client key were in flight at once, and this one lost.
 *
 * <p>The lookup that normally answers a retry runs before the write, so it cannot see a row
 * that another request has not committed yet. The unique constraint catches it instead — which
 * is the point of having the constraint rather than trusting the lookup — but by then
 * Hibernate's session is spent, and reading back the winner's row would need a transaction this
 * one no longer has.
 *
 * <p>So the loser is told to ask again. Nothing was written twice, and the retry finds the
 * winner's row through the ordinary lookup and gets the ordinary answer. <strong>Retryable, and
 * meant to be retried</strong> — which is what separates it from
 * {@link IdempotencyKeyReuseException}, despite both being conflicts.
 */
public class ConcurrentRequestException extends RuntimeException {

    public ConcurrentRequestException(String message) {
        super(message);
    }
}
