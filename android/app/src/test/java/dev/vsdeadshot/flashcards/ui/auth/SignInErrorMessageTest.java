package dev.vsdeadshot.flashcards.ui.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import dev.vsdeadshot.flashcards.R;
import org.junit.Test;

/**
 * What a refused sign-in tells the person who typed the passphrase, given only the status.
 *
 * <p>No Robolectric: the mapping touches no framework, and a string resource id is an int on the
 * classpath. Same arrangement as {@code GenerateErrorMessageTest}.
 */
public class SignInErrorMessageTest {

    @Test
    public void aWrongPassphraseSaysSoAndNothingMore() {
        // 401 carries no body: the endpoint answers the same way for a wrong passphrase whether
        // or not it was close, so there is nothing more specific to say and nothing to hint at.
        assertEquals(R.string.auth_error_refused, AuthViewModel.messageFor(401));
    }

    /**
     * A blank passphrase is refused by the server's own validation before bcrypt runs, so it
     * arrives as a 400 rather than a 401. It is the same thing to say: what was typed was not
     * accepted.
     */
    @Test
    public void anEmptyBodyIsTheSameAnswerAsAWrongOne() {
        assertEquals(R.string.auth_error_refused, AuthViewModel.messageFor(400));
    }

    /**
     * The case the default would get most wrong. 503 means the server has no passphrase
     * configured at all, so nothing typed here will ever work — reporting it as a passphrase
     * that was not accepted sends somebody round a loop with no exit, which is exactly the
     * failure the backend's own malformed-hash fix was written to stop.
     */
    @Test
    public void aServerWithNoPassphraseIsNotAWrongPassphrase() {
        assertEquals(R.string.auth_error_unavailable, AuthViewModel.messageFor(503));
        assertNotEquals("retyping it is precisely what will not help",
                R.string.auth_error_refused, AuthViewModel.messageFor(503));
    }

    /**
     * And the inverse. The limit counts failures over a rolling fifteen minutes and nothing is
     * wrong with the passphrase or the server; reporting it as either would be misleading in
     * opposite directions.
     */
    @Test
    public void aSpentAllowanceIsNeitherAWrongPassphraseNorABrokenServer() {
        assertEquals(R.string.auth_error_too_many, AuthViewModel.messageFor(429));
        assertNotEquals(R.string.auth_error_refused, AuthViewModel.messageFor(429));
        assertNotEquals(R.string.auth_error_unavailable, AuthViewModel.messageFor(429));
    }

    @Test
    public void anythingElseIsTheServerHavingABadMoment() {
        assertEquals(R.string.auth_error_server, AuthViewModel.messageFor(500));
        assertEquals(R.string.auth_error_server, AuthViewModel.messageFor(502));
    }

    @Test
    public void theMessagesAreActuallyDifferentStrings() {
        // Distinct ids are what makes the assertions above mean anything; two names pointing at
        // one resource would let every case pass while saying the same unhelpful thing.
        assertNotEquals(R.string.auth_error_refused, R.string.auth_error_too_many);
        assertNotEquals(R.string.auth_error_refused, R.string.auth_error_unavailable);
        assertNotEquals(R.string.auth_error_refused, R.string.auth_error_server);
        assertNotEquals(R.string.auth_error_offline, R.string.auth_error_server);
        assertNotEquals(R.string.auth_error_empty, R.string.auth_error_refused);
    }
}
