package dev.vsdeadshot.flashcards.service;

/**
 * Too many failed sign-ins, so this one was refused without checking the passphrase.
 *
 * <p>Carries the wait rather than the count. Saying how many attempts remain would tell somebody
 * guessing exactly how hard they may push, and a client that is not guessing has no use for it.
 */
public class LoginLimitExceededException extends RuntimeException {

    private final long retryAfterSeconds;

    public LoginLimitExceededException(long retryAfterSeconds) {
        super("Too many sign-in attempts. Try again in " + retryAfterSeconds + " seconds.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
