package dev.vsdeadshot.flashcards.ui.cards;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import dev.vsdeadshot.flashcards.R;
import org.junit.Test;

/**
 * Which failure a person is told about, given only the status.
 *
 * <p>No Robolectric: the mapping touches no framework, and a string resource id is an int on
 * the classpath. Same reasoning as the remote and mapper tests.
 */
public class GenerateErrorMessageTest {

    @Test
    public void anUpstreamThatDidNotAnswerIsTheOneFailureWorthRetrying() {
        assertEquals("503 is the backend saying the model did not answer, which a retry can fix",
                R.string.generate_error_busy, GenerateViewModel.messageFor(503));
    }

    @Test
    public void aModelWithNothingToAddAsksForADifferentFocus() {
        assertEquals("422 is the generator refusing this prompt, not failing at it",
                R.string.generate_error_refused, GenerateViewModel.messageFor(422));
    }

    @Test
    public void aRejectedRequestSaysSoRatherThanInvitingAnEndlessRetry() {
        // The bodyless 500 ApiExceptionHandler leaves unmapped on purpose. This is the case the
        // ternary that used to live here got wrong, and it is the likeliest failure of the lot:
        // a stale key or a renamed model, neither of which waiting will repair.
        assertEquals("500 means our own request was rejected, so waiting achieves nothing",
                R.string.generate_error_misconfigured, GenerateViewModel.messageFor(500));
    }

    @Test
    public void anUnauthenticatedClientIsAlsoSomethingOnlyTheServerCanFix() {
        // 401 carries no body at all -- the filter rejects before any handler runs -- so status
        // is the whole of what there is to go on.
        assertEquals("a rejected API key is configuration, not a busy moment",
                R.string.generate_error_misconfigured, GenerateViewModel.messageFor(401));
    }

    @Test
    public void theFourMessagesAreActuallyDifferentStrings() {
        // Distinct ids are what makes the assertions above mean anything; two names pointing at
        // one resource would let every case pass while saying the same unhelpful thing.
        assertNotEquals(R.string.generate_error_busy, R.string.generate_error_refused);
        assertNotEquals(R.string.generate_error_busy, R.string.generate_error_misconfigured);
        assertNotEquals(R.string.generate_error_refused, R.string.generate_error_misconfigured);
        assertNotEquals(R.string.generate_error_offline, R.string.generate_error_misconfigured);
    }
}
