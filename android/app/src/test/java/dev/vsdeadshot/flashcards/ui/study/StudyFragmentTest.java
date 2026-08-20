package dev.vsdeadshot.flashcards.ui.study;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import androidx.room.Room;
import androidx.work.testing.WorkManagerTestInitHelper;
import dev.vsdeadshot.flashcards.R;
import dev.vsdeadshot.flashcards.data.local.CardEntity;
import dev.vsdeadshot.flashcards.data.local.FlashcardsDatabase;
import dev.vsdeadshot.flashcards.data.local.TopicEntity;
import dev.vsdeadshot.flashcards.ui.Graph;
import dev.vsdeadshot.flashcards.ui.MainActivity;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The screen as it is actually assembled — real fragment, real {@code Graph}, real executor.
 *
 * <p>{@code StudyViewModelTest} pins what the loop does. This pins that the views follow it: that
 * a question is not shown with its answer, that the button turns the card over, and that answering
 * moves on. There is no emulator on this machine, so this is the only thing that runs the layout
 * and the fragment together at all.
 */
@RunWith(RobolectricTestRunner.class)
// FlashcardsApp is kept out of this: Robolectric does not create the app's content providers, so
// androidx.startup never initialises WorkManager and onCreate's call to it throws.
@Config(application = Application.class)
public class StudyFragmentTest {

    /**
     * Long enough to cover the hold, the card leaving and the next one arriving.
     *
     * <p>Answering is deliberately not instant: the chosen button lifts, the choice sits there
     * long enough to be read, and only then does the card leave and the review get written. A
     * test that idled the looper without advancing it would see none of that happen and would
     * report the screen as stuck.
     */
    private static final Duration ANSWER = Duration.ofMillis(900);

    /** The two halves of the flip, and then some. */
    private static final Duration FLIP = Duration.ofMillis(600);

    private FlashcardsDatabase db;

    @Before
    public void setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(RuntimeEnvironment.getApplication());
        db = Room.inMemoryDatabaseBuilder(
                        RuntimeEnvironment.getApplication(), FlashcardsDatabase.class)
                .allowMainThreadQueries()
                .build();
        // The screen reads through Graph, so the cache it reads has to be one this test can seed.
        Graph.installDatabase(db);
        cacheTopic();
    }

    @After
    public void tearDown() {
        Graph.reset();
        db.close();
    }

    @Test
    public void theQuestionIsShownAndTheAnswerIsNot() throws Exception {
        cacheCard(1L, "What does the OOM killer do?", "Picks a process and kills it.");

        MainActivity activity = open();

        assertEquals("What does the OOM killer do?", text(activity, R.id.study_front));
        assertEquals("the answer is a whole second face, and it is not the one facing out",
                View.GONE, visibility(activity, R.id.study_card_back));
        assertEquals("a card whose answer is already up cannot be answered honestly",
                View.GONE, visibility(activity, R.id.study_answers));
        assertEquals(View.VISIBLE, visibility(activity, R.id.study_show_answer));
        assertEquals("there is nothing to compare against yet",
                View.GONE, visibility(activity, R.id.study_wrote_block));
        assertEquals(View.VISIBLE, visibility(activity, R.id.study_attempt_block));
        assertEquals("Operating Systems", text(activity, R.id.study_topic));
    }

    @Test
    public void showingTheAnswerRevealsItAndTheButtons() throws Exception {
        cacheCard(1L, "front", "Picks a process and kills it.");
        MainActivity activity = open();

        activity.findViewById(R.id.study_show_answer).performClick();
        advance(FLIP);

        assertEquals("Picks a process and kills it.", text(activity, R.id.study_back));
        assertEquals(View.VISIBLE, visibility(activity, R.id.study_card_back));
        assertEquals("the face that was showing has turned away", View.GONE,
                visibility(activity, R.id.study_card));
        assertEquals(View.VISIBLE, visibility(activity, R.id.study_answers));
        assertEquals("nothing left to show", View.GONE,
                visibility(activity, R.id.study_show_answer));
        assertEquals("the field folds away with the turn", View.GONE,
                visibility(activity, R.id.study_attempt_block));
    }

    /**
     * The whole reason the field is there. Nothing grades it, so if it did not survive the reveal
     * there would be no point in having typed it.
     */
    @Test
    public void whatWasTypedIsKeptToCompareAgainstTheAnswer() throws Exception {
        cacheCard(1L, "front", "Picks a process and kills it.");
        MainActivity activity = open();

        type(activity, "  it picks a process  ");
        activity.findViewById(R.id.study_show_answer).performClick();
        advance(FLIP);

        assertEquals(View.VISIBLE, visibility(activity, R.id.study_wrote_block));
        assertEquals("trimmed, because trailing space in a quotation block reads as a typo",
                "it picks a process", text(activity, R.id.study_wrote));
    }

    /** An empty quotation block is worse than no block, so there is no block. */
    @Test
    public void typingNothingLeavesNoQuotationBlock() throws Exception {
        cacheCard(1L, "front", "back");
        MainActivity activity = open();

        type(activity, "   ");
        activity.findViewById(R.id.study_show_answer).performClick();
        advance(FLIP);

        assertEquals(View.GONE, visibility(activity, R.id.study_wrote_block));
    }

    @Test
    public void answeringMovesOnToTheNextQuestion() throws Exception {
        cacheCard(1L, "first", "back one");
        cacheCard(2L, "second", "back two");
        MainActivity activity = open();
        activity.findViewById(R.id.study_show_answer).performClick();
        advance(FLIP);

        activity.findViewById(R.id.study_confidence_4).performClick();
        advance(ANSWER);
        settle();

        assertEquals("second", text(activity, R.id.study_front));
        assertEquals("the next question must not arrive with the last answer under it",
                View.GONE, visibility(activity, R.id.study_card_back));
        assertEquals(View.VISIBLE, visibility(activity, R.id.study_card));
    }

    /**
     * The attempt belongs to the card it was typed against. Carrying it over would put somebody
     * else's answer under a question it was never written for.
     */
    @Test
    public void theNextCardArrivesWithAnEmptyAnswerField() throws Exception {
        cacheCard(1L, "first", "back one");
        cacheCard(2L, "second", "back two");
        MainActivity activity = open();
        type(activity, "something about the first card");
        activity.findViewById(R.id.study_show_answer).performClick();
        advance(FLIP);

        activity.findViewById(R.id.study_confidence_4).performClick();
        advance(ANSWER);
        settle();

        assertEquals("", text(activity, R.id.study_attempt));
        assertEquals(View.GONE, visibility(activity, R.id.study_wrote_block));
    }

    @Test
    public void anEmptyCacheSaysSoRatherThanCongratulatingAnyone() throws Exception {
        MainActivity activity = open();

        assertEquals(View.VISIBLE, visibility(activity, R.id.study_empty));
        assertEquals(activity.getString(R.string.study_no_cards_title),
                text(activity, R.id.study_empty_title));
        assertEquals(activity.getString(R.string.study_no_cards_body),
                text(activity, R.id.study_empty_body));
        assertEquals(View.GONE, visibility(activity, R.id.study_faces));
        assertEquals("a due count of zero is worse than no due count",
                View.GONE, visibility(activity, R.id.study_header));
    }

    @Test
    public void aCaughtUpCacheIsToldApartFromAnEmptyOne() throws Exception {
        cacheCard(1L, "front", "back");
        MainActivity activity = open();
        activity.findViewById(R.id.study_show_answer).performClick();
        advance(FLIP);

        activity.findViewById(R.id.study_confidence_5).performClick();
        advance(ANSWER);
        settle();

        assertEquals(activity.getString(R.string.study_caught_up_title),
                text(activity, R.id.study_empty_title));
        assertEquals(activity.getString(R.string.study_caught_up_body),
                text(activity, R.id.study_empty_body));
        assertNotEquals("the two states must not share a sentence",
                activity.getString(R.string.study_no_cards_title),
                text(activity, R.id.study_empty_title));
    }

    // ---- fixtures -----------------------------------------------------------------------------

    private MainActivity open() throws InterruptedException {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        settle();
        return activity;
    }

    /**
     * Waits for the read, then delivers it.
     *
     * <p>{@code Graph.io()} is one thread handing out work in order, so a task queued after the
     * view model's read has to wait for it — which makes this a wait rather than a sleep. The
     * value then sits on the main looper, which Robolectric does not run unasked.
     */
    private void settle() throws InterruptedException {
        shadowOf(Looper.getMainLooper()).idle();
        CountDownLatch behindTheRead = new CountDownLatch(1);
        Graph.io().execute(behindTheRead::countDown);
        behindTheRead.await(10, TimeUnit.SECONDS);
        shadowOf(Looper.getMainLooper()).idle();
    }

    /**
     * Runs the main looper forward, animations included.
     *
     * <p>Plain {@code idle()} runs what is already due and stops. This screen deliberately puts
     * time between a tap and its consequence — a card turns over, a choice is held long enough to
     * be seen — so a test that never advances the clock sees a screen that never moves.
     */
    private void advance(Duration duration) {
        shadowOf(Looper.getMainLooper()).idleFor(duration);
    }

    private static void type(MainActivity activity, String attempt) {
        ((EditText) activity.findViewById(R.id.study_attempt)).setText(attempt);
    }

    private static String text(MainActivity activity, int id) {
        return ((TextView) activity.findViewById(id)).getText().toString();
    }

    private static int visibility(MainActivity activity, int id) {
        return activity.findViewById(id).getVisibility();
    }

    private void cacheCard(long id, String front, String back) {
        CardEntity card = new CardEntity();
        card.id = id;
        card.serverId = id;
        card.topicId = 1L;
        card.front = front;
        card.back = back;
        card.easeFactor = 2.5d;
        card.intervalDays = 1;
        card.repetitions = 1;
        card.dueDate = LocalDate.now().minusDays(1);
        db.cards().upsertAll(List.of(card));
    }

    private void cacheTopic() {
        TopicEntity topic = new TopicEntity();
        topic.id = 1L;
        topic.name = "Operating Systems";
        topic.slug = "operating-systems";
        topic.createdAt = Instant.parse("2026-01-01T00:00:00Z");
        db.topics().upsertAll(List.of(topic));
    }
}
