package dev.vsdeadshot.flashcards.service;

/**
 * The same client key arrived with a different payload.
 *
 * <p>A key names one operation the client queued, and the payload was fixed at that moment, so
 * this cannot happen to a client that mints a key per operation. Returning the original result
 * instead would be defensible — it is what a strict reading of idempotency asks for — but it
 * would also hide a bug the client has no other way of discovering, and the wrong outcome
 * would be recorded under the right key forever.
 *
 * <p>Not retryable. A client that retries this gets it again, permanently.
 */
public class IdempotencyKeyReuseException extends RuntimeException {

    public IdempotencyKeyReuseException(String message) {
        super(message);
    }
}
