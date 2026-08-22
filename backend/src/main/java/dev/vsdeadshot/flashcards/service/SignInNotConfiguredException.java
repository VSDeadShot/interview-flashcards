package dev.vsdeadshot.flashcards.service;

/**
 * No passphrase has been configured, so nothing can be signed in against.
 *
 * <p>Answered {@code 503} rather than {@code 401}: the caller's credentials were never the
 * problem, and telling them their passphrase was wrong would send them looking for a fault
 * that is on this side. Same shape as generation answering {@code 503} without a Gemini key --
 * a capability that is absent, not a request that was refused.
 */
public class SignInNotConfiguredException extends RuntimeException {

    public SignInNotConfiguredException() {
        super("Sign-in is not configured on this server.");
    }
}
