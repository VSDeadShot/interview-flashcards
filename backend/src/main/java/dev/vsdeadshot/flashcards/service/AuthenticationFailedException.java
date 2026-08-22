package dev.vsdeadshot.flashcards.service;

/**
 * The passphrase presented at sign-in was not the passphrase.
 *
 * <p>Carries no message and no detail, and {@code ApiExceptionHandler} answers it with a
 * bodyless {@code 401}. There is nothing useful to say to somebody who did not authenticate,
 * and anything said would only describe the check they failed.
 */
public class AuthenticationFailedException extends RuntimeException {

    public AuthenticationFailedException() {
        super(null, null, false, false);
    }
}
