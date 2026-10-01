package dev.vsdeadshot.flashcards.ui.cards;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import dev.vsdeadshot.flashcards.R;
import dev.vsdeadshot.flashcards.data.remote.Failure;
import org.junit.Test;

/**
 * Which failure a person adding a topic is told about, given the kind of failure and the status.
 *
 * <p>No Robolectric: the mapping touches no framework, and a string resource id is an int on the
 * classpath. Same reasoning as GenerateErrorMessageTest.
 */
public class NewTopicErrorMessageTest {

    /** No status: the server never answered. */
    private static final int NONE = 0;

    @Test
    public void noConnectionSaysAddingATopicNeedsOne() {
        assertEquals("a dead radio is the one cause somebody can fix on the spot",
                R.string.new_topic_error_offline, NewTopicViewModel.messageFor(Failure.OFFLINE, NONE));
    }

    @Test
    public void aSlowServerIsReportedAsSlowAndNotAsOffline() {
        assertEquals("a timeout is the server waking, not the radio",
                R.string.new_topic_error_slow, NewTopicViewModel.messageFor(Failure.SLOW, NONE));
        assertEquals("a gateway answering for a server that is not up yet is the same thing",
                R.string.new_topic_error_slow, NewTopicViewModel.messageFor(Failure.SLOW, 502));
    }

    @Test
    public void aConnectionThatBrokeOffIsNeitherSlowNorOffline() {
        assertEquals("anything else that went wrong on the way is worth one more try",
                R.string.new_topic_error_broken, NewTopicViewModel.messageFor(Failure.BROKEN, NONE));
    }

    @Test
    public void aNameWithNothingSluggableInItAsksForALetterOrDigit() {
        assertEquals("400 is a name the server could not make a slug from, like '!!!'",
                R.string.new_topic_error_invalid,
                NewTopicViewModel.messageFor(Failure.ANSWERED, 400));
    }

    @Test
    public void aRequestWithNoSessionAsksForASignIn() {
        // 401 carries no body -- the filter rejects before any handler runs -- and since tokens
        // it means there is no session, or one that could not be renewed. Signing in fixes both.
        assertEquals("a 401 is the one failure only signing in can fix",
                R.string.new_topic_error_signed_out,
                NewTopicViewModel.messageFor(Failure.ANSWERED, 401));
    }

    @Test
    public void aNameAlreadyTakenSaysSo() {
        assertEquals("409 is a slug this user already has, which is not a failure to retry",
                R.string.new_topic_error_duplicate,
                NewTopicViewModel.messageFor(Failure.ANSWERED, 409));
    }

    @Test
    public void anythingElseTheServerAnsweredIsNotWorthRetrying() {
        assertEquals("a bodyless 500 is the server's fault, and retrying will not change it",
                R.string.new_topic_error_misconfigured,
                NewTopicViewModel.messageFor(Failure.ANSWERED, 500));
        assertEquals("as is any status this screen has no specific answer for",
                R.string.new_topic_error_misconfigured,
                NewTopicViewModel.messageFor(Failure.ANSWERED, 418));
    }

    @Test
    public void everyFailureSaysSomethingDifferent() {
        int[] messages = {
            R.string.new_topic_error_offline,
            R.string.new_topic_error_slow,
            R.string.new_topic_error_broken,
            R.string.new_topic_error_invalid,
            R.string.new_topic_error_signed_out,
            R.string.new_topic_error_duplicate,
            R.string.new_topic_error_misconfigured,
        };
        for (int i = 0; i < messages.length; i++) {
            for (int j = i + 1; j < messages.length; j++) {
                assertNotEquals("two failures answered differently must not share a message",
                        messages[i], messages[j]);
            }
        }
    }
}
