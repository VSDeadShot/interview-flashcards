package dev.vsdeadshot.flashcards.ui.cards;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import dev.vsdeadshot.flashcards.R;
import dev.vsdeadshot.flashcards.data.remote.Failure;
import org.junit.Test;

/**
 * Which failure a person is told about, given the kind of failure and the status.
 *
 * <p>No Robolectric: the mapping touches no framework, and a string resource id is an int on
 * the classpath. Same reasoning as the remote and mapper tests.
 */
public class GenerateErrorMessageTest {

    /** No status: the server never answered. */
    private static final int NONE = 0;

    @Test
    public void anUpstreamThatDidNotAnswerIsTheOneFailureWorthRetrying() {
        assertEquals("503 is the backend saying the model did not answer, which a retry can fix",
                R.string.generate_error_busy, GenerateViewModel.messageFor(Failure.ANSWERED, 503));
    }

    @Test
    public void aModelWithNothingToAddAsksForADifferentFocus() {
        assertEquals("422 is the generator refusing this prompt, not failing at it",
                R.string.generate_error_refused,
                GenerateViewModel.messageFor(Failure.ANSWERED, 422));
    }

    @Test
    public void aRejectedRequestSaysSoRatherThanInvitingAnEndlessRetry() {
        // The bodyless 500 ApiExceptionHandler leaves unmapped on purpose. This is the case the
        // ternary that used to live here got wrong, and it is the likeliest failure of the lot:
        // a stale key or a renamed model, neither of which waiting will repair.
        assertEquals("500 means our own request was rejected, so waiting achieves nothing",
                R.string.generate_error_misconfigured,
                GenerateViewModel.messageFor(Failure.ANSWERED, 500));
    }

    @Test
    public void anUnauthenticatedClientIsAlsoSomethingOnlyTheServerCanFix() {
        // 401 carries no body at all -- the filter rejects before any handler runs -- so status
        // is the whole of what there is to go on.
        assertEquals("a rejected API key is configuration, not a busy moment",
                R.string.generate_error_misconfigured,
                GenerateViewModel.messageFor(Failure.ANSWERED, 401));
    }

    /**
     * The one the default would get most wrong. A spent daily allowance is not a broken server,
     * and the misconfigured message tells somebody that retrying will never help — which is the
     * opposite of true here, since the limit comes back at midnight.
     */
    @Test
    public void aSpentDailyAllowanceIsNotABrokenServer() {
        assertEquals("429 is the day's generation limit, which resets rather than staying broken",
                R.string.generate_error_limit, GenerateViewModel.messageFor(Failure.ANSWERED, 429));
        assertNotEquals("and it must not be reported as something a retry can never fix",
                R.string.generate_error_misconfigured,
                GenerateViewModel.messageFor(Failure.ANSWERED, 429));
    }

    /**
     * A topic the server does not know — the cache holding one the server no longer has — used
     * to fall through to "not set up correctly", which sends somebody looking for a server fault
     * when a sync is what would fix it.
     */
    @Test
    public void anUnknownTopicAsksForASyncRatherThanBlamingTheServer() {
        assertEquals("404 on generate is a topic the server does not have",
                R.string.generate_error_missing_topic,
                GenerateViewModel.messageFor(Failure.ANSWERED, 404));
        assertNotEquals(R.string.generate_error_misconfigured,
                GenerateViewModel.messageFor(Failure.ANSWERED, 404));
    }

    @Test
    public void aServerThatIsWakingIsNeitherOfflineNorMisconfigured() {
        assertEquals(R.string.generate_error_slow, GenerateViewModel.messageFor(Failure.SLOW, NONE));
        assertEquals("a router's gateway page is the server waking, whatever its status",
                R.string.generate_error_slow, GenerateViewModel.messageFor(Failure.SLOW, 502));
        assertNotEquals(R.string.generate_error_offline,
                GenerateViewModel.messageFor(Failure.SLOW, NONE));
        assertNotEquals(R.string.generate_error_misconfigured,
                GenerateViewModel.messageFor(Failure.SLOW, 502));
    }

    @Test
    public void noConnectionSaysSo() {
        assertEquals(R.string.generate_error_offline,
                GenerateViewModel.messageFor(Failure.OFFLINE, NONE));
    }

    @Test
    public void anExchangeThatFailedSomeOtherWayIsNotReportedAsOffline() {
        assertEquals(R.string.generate_error_broken,
                GenerateViewModel.messageFor(Failure.BROKEN, NONE));
        assertNotEquals(R.string.generate_error_offline,
                GenerateViewModel.messageFor(Failure.BROKEN, NONE));
    }

    @Test
    public void theMessagesAreActuallyDifferentStrings() {
        // Distinct ids are what makes the assertions above mean anything; two names pointing at
        // one resource would let every case pass while saying the same unhelpful thing.
        assertNotEquals(R.string.generate_error_busy, R.string.generate_error_refused);
        assertNotEquals(R.string.generate_error_busy, R.string.generate_error_misconfigured);
        assertNotEquals(R.string.generate_error_refused, R.string.generate_error_misconfigured);
        assertNotEquals(R.string.generate_error_offline, R.string.generate_error_misconfigured);
        assertNotEquals(R.string.generate_error_limit, R.string.generate_error_busy);
        assertNotEquals(R.string.generate_error_limit, R.string.generate_error_misconfigured);
        assertNotEquals(R.string.generate_error_missing_topic, R.string.generate_error_misconfigured);
        assertNotEquals(R.string.generate_error_slow, R.string.generate_error_offline);
        assertNotEquals(R.string.generate_error_slow, R.string.generate_error_busy);
        assertNotEquals(R.string.generate_error_broken, R.string.generate_error_offline);
    }
}
