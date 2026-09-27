package dev.vsdeadshot.flashcards.ui.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import dev.vsdeadshot.flashcards.R;
import dev.vsdeadshot.flashcards.data.remote.Failure;
import org.junit.Test;

/**
 * What a failed sign-in tells the person who typed the passphrase, given the kind of failure and
 * the status the server answered with.
 *
 * <p>No Robolectric: the mapping touches no framework, and a string resource id is an int on the
 * classpath. Same arrangement as {@code GenerateErrorMessageTest}.
 */
public class SignInErrorMessageTest {

    /** No status: the server never answered. */
    private static final int NONE = 0;

    @Test
    public void aWrongPassphraseSaysSoAndNothingMore() {
        // 401 carries no body: the endpoint answers the same way for a wrong passphrase whether
        // or not it was close, so there is nothing more specific to say and nothing to hint at.
        assertEquals(R.string.auth_error_refused, AuthViewModel.messageFor(Failure.ANSWERED, 401));
    }

    /**
     * A blank passphrase is refused by the server's own validation before bcrypt runs, so it
     * arrives as a 400 rather than a 401. It is the same thing to say: what was typed was not
     * accepted.
     */
    @Test
    public void anEmptyBodyIsTheSameAnswerAsAWrongOne() {
        assertEquals(R.string.auth_error_refused, AuthViewModel.messageFor(Failure.ANSWERED, 400));
    }

    /**
     * The case the default would get most wrong. A 503 with a problem body means the server has
     * no passphrase configured at all, so nothing typed here will ever work — reporting it as a
     * passphrase that was not accepted sends somebody round a loop with no exit.
     */
    @Test
    public void aServerWithNoPassphraseIsNotAWrongPassphrase() {
        assertEquals(R.string.auth_error_unavailable,
                AuthViewModel.messageFor(Failure.ANSWERED, 503));
        assertNotEquals("retyping it is precisely what will not help",
                R.string.auth_error_refused, AuthViewModel.messageFor(Failure.ANSWERED, 503));
    }

    /**
     * And the inverse. The limit counts failures over a rolling fifteen minutes and nothing is
     * wrong with the passphrase or the server; reporting it as either would be misleading in
     * opposite directions.
     */
    @Test
    public void aSpentAllowanceIsNeitherAWrongPassphraseNorABrokenServer() {
        assertEquals(R.string.auth_error_too_many, AuthViewModel.messageFor(Failure.ANSWERED, 429));
        assertNotEquals(R.string.auth_error_refused,
                AuthViewModel.messageFor(Failure.ANSWERED, 429));
        assertNotEquals(R.string.auth_error_unavailable,
                AuthViewModel.messageFor(Failure.ANSWERED, 429));
    }

    @Test
    public void anythingElseTheServerAnsweredIsItHavingABadMoment() {
        assertEquals(R.string.auth_error_server, AuthViewModel.messageFor(Failure.ANSWERED, 500));
        assertEquals(R.string.auth_error_server, AuthViewModel.messageFor(Failure.ANSWERED, 404));
    }

    /**
     * The bug this was written for. A Render cold start takes about two minutes, longer than
     * the client waits, and used to be reported as "Signing in needs a connection" on a device
     * that was online the whole time.
     */
    @Test
    public void aServerThatIsWakingIsNotAMissingConnection() {
        assertEquals(R.string.auth_error_slow, AuthViewModel.messageFor(Failure.SLOW, NONE));
        assertNotEquals("the device reached the server; the server was slow to answer",
                R.string.auth_error_offline, AuthViewModel.messageFor(Failure.SLOW, NONE));
    }

    /**
     * A router's gateway page arrives with a status, but it is still the server waking rather
     * than the server refusing anything, so the status must not decide it.
     */
    @Test
    public void aGatewayErrorIsTheServerWakingWhateverItsStatus() {
        assertEquals(R.string.auth_error_slow, AuthViewModel.messageFor(Failure.SLOW, 502));
        assertEquals(R.string.auth_error_slow, AuthViewModel.messageFor(Failure.SLOW, 503));
        assertNotEquals("a waking server is not a server with sign-in switched off",
                R.string.auth_error_unavailable, AuthViewModel.messageFor(Failure.SLOW, 503));
    }

    @Test
    public void noConnectionSaysSo() {
        assertEquals(R.string.auth_error_offline, AuthViewModel.messageFor(Failure.OFFLINE, NONE));
    }

    @Test
    public void anExchangeThatFailedSomeOtherWayIsNotReportedAsOffline() {
        assertEquals(R.string.auth_error_broken, AuthViewModel.messageFor(Failure.BROKEN, NONE));
        assertNotEquals(R.string.auth_error_offline,
                AuthViewModel.messageFor(Failure.BROKEN, NONE));
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
        assertNotEquals(R.string.auth_error_slow, R.string.auth_error_offline);
        assertNotEquals(R.string.auth_error_slow, R.string.auth_error_unavailable);
        assertNotEquals(R.string.auth_error_broken, R.string.auth_error_offline);
        assertNotEquals(R.string.auth_error_broken, R.string.auth_error_server);
    }
}
