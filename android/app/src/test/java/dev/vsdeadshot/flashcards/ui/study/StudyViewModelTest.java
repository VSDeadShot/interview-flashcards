package dev.vsdeadshot.flashcards.ui.study;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.os.Looper;
import androidx.room.Room;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.testing.WorkManagerTestInitHelper;
import dev.vsdeadshot.flashcards.data.ReviewRepository;
import dev.vsdeadshot.flashcards.data.StudyRepository;
import dev.vsdeadshot.flashcards.data.local.CardEntity;
import dev.vsdeadshot.flashcards.data.local.FlashcardsDatabase;
import dev.vsdeadshot.flashcards.data.local.TopicEntity;
import dev.vsdeadshot.flashcards.data.sync.SyncScheduler;
import dev.vsdeadshot.flashcards.ui.study.StudyViewModel.StudyState;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The study loop, and the two places it has to be careful.
 *
 * <p>One is that an answer must never be on screen under the next question. The other is that a
 * reload has to tell a rotation apart from a genuinely different card, or turning the phone over
 * mid-question would take the answer away.
 */
@RunWith(RobolectricTestRunner.class)
// FlashcardsApp is kept out of this: Robolectric does not create the app's content providers, so
// androidx.startup never initialises WorkManager and onCreate's call to it throws. The test
// WorkManager set up below is what answering a card talks to instead.
@Config(application = Application.class)
public class StudyViewModelTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 17);
    private static final Clock FIXED =
            Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    /** Runs the work on the calling thread: the hop is what is being stood in for, not the thread. */
    private static final Executor DIRECT = Runnable::run;

    private FlashcardsDatabase db;
    private StudyViewModel model;

    @Before
    public void setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(RuntimeEnvironment.getApplication());
        db = Room.inMemoryDatabaseBuilder(
                        RuntimeEnvironment.getApplication(), FlashcardsDatabase.class)
                .allowMainThreadQueries()
                // Room refreshes its invalidation tracker on the query executor. Running that
                // inline is what lets a write and the notification it causes be observed within
                // one test method rather than raced against.
                .setQueryExecutor(DIRECT)
                .build();
        cacheTopic();
    }

    @After
    public void tearDown() {
        if (model != null) {
            // Unregisters the invalidation observer. Room holds it strongly, so a test that left
            // it behind would keep its view model alive for the rest of the suite.
            model.onCleared();
        }
        db.close();
    }

    @Test
    public void theFirstCardIsShownFaceDown() {
        cacheCard(1L, TODAY);
        cacheCard(2L, TODAY);

        open();

        assertEquals(1L, state().view().card().id);
        assertFalse("a question with its answer already showing is not a question",
                state().revealed());
        assertEquals(2, state().view().dueCount());
    }

    @Test
    public void revealingTurnsTheCardOverWithoutChangingIt() {
        cacheCard(1L, TODAY);
        open();

        model.reveal();

        assertTrue(state().revealed());
        assertEquals("revealing is not advancing", 1L, state().view().card().id);
    }

    @Test
    public void answeringMovesToTheNextCardFaceDown() {
        cacheCard(1L, TODAY);
        cacheCard(2L, TODAY);
        open();
        model.reveal();

        model.answer(5);
        idle();

        assertEquals(2L, state().view().card().id);
        assertFalse("the previous answer must not be left sitting under the next question",
                state().revealed());
    }

    @Test
    public void answeringTheLastCardSaysCaughtUpRatherThanEmpty() {
        cacheCard(1L, TODAY);
        open();

        model.answer(4);
        idle();

        assertFalse(state().view().hasCard());
        assertFalse("there is a card, it is just not due until tomorrow",
                state().view().isEmptyCache());
    }

    @Test
    public void answeringAsksForASync() throws Exception {
        cacheCard(1L, TODAY);
        open();

        model.answer(3);
        idle();

        assertEquals("an answer nobody has been told about is the thing the outbox exists to"
                + " stop being permanent", 1, immediateSyncRequests());
    }

    /**
     * The rotation case. The screen is resumed with the same card still at the head, so the answer
     * somebody was reading stays where it was.
     */
    @Test
    public void reloadKeepsTheAnswerUpWhenTheCardHasNotChanged() {
        cacheCard(1L, TODAY);
        open();
        model.reveal();

        model.reload();
        idle();

        assertEquals(1L, state().view().card().id);
        assertTrue("turning the phone over is not a request to take the answer away",
                state().revealed());
    }

    /** The other half of that condition: a different card must arrive face down. */
    @Test
    public void reloadStartsADifferentCardFaceDown() {
        cacheCard(1L, TODAY);
        cacheCard(2L, TODAY);
        open();
        model.reveal();

        // As if a sync had archived the card while the screen was away.
        db.cards().deleteById(1L);
        model.reload();
        idle();

        assertEquals(2L, state().view().card().id);
        assertFalse(state().revealed());
    }

    /**
     * A card archived on another device between the read and the tap. The answer is lost, which is
     * the cheaper of the two failures — the alternative is a crash on a button press.
     */
    @Test
    public void answeringACardThatIsGoneReloadsInsteadOfFailing() {
        cacheCard(1L, TODAY);
        cacheCard(2L, TODAY);
        open();

        db.cards().deleteById(1L);
        model.answer(5);
        idle();

        assertEquals("the screen carries on with what is actually there",
                2L, state().view().card().id);
    }

    @Test
    public void thereIsNothingToAnswerWhenNothingIsDue() throws Exception {
        open();

        model.answer(5);
        idle();

        assertFalse(state().view().hasCard());
        assertTrue("nothing has ever been here, which is not the same as being caught up",
                state().view().isEmptyCache());
        assertEquals("a tap with no card behind it must not queue a review of nothing",
                0, immediateSyncRequests());
    }

    /**
     * The empty screen tells somebody to run a sync. This is that sync arriving.
     *
     * <p>Before this, the cards a sync fetched could not reach the study screen at all until the
     * tab was left and reopened — so the one instruction the empty state gives appeared to do
     * nothing.
     */
    @Test
    public void cardsFetchedWhileTheQueueIsEmptyArriveOnTheirOwn() {
        open();
        assertFalse("nothing is cached yet, so there is nothing to answer",
                state().view().hasCard());

        // As the sync writes them: the same database instance, from outside this view model.
        cacheCard(1L, TODAY);
        idle();

        assertEquals("a sync that fetched a card and could not show it is the empty state lying",
                1L, state().view().card().id);
        assertFalse("a card arriving unasked must still arrive face down", state().revealed());
    }

    /**
     * The other half of the rule, and the reason it is a rule rather than a plain subscription:
     * a card being answered is never taken away by a write from somewhere else.
     */
    @Test
    public void aSyncDoesNotReplaceTheCardSomebodyIsAnswering() {
        cacheCard(1L, TODAY);
        open();
        model.reveal();

        // A sync landing mid-question, carrying a card that sorts ahead of the one on screen.
        cacheCard(0L, TODAY.minusDays(3));
        idle();

        assertEquals("the question must not change between the reveal and the button press",
                1L, state().view().card().id);
        assertTrue("nor may the answer be taken away while it is being read", state().revealed());
    }

    // ---- fixtures -----------------------------------------------------------------------------

    private void open() {
        model = new StudyViewModel(RuntimeEnvironment.getApplication(), db,
                new StudyRepository(db, FIXED), new ReviewRepository(db, FIXED), DIRECT);
        idle();
    }

    private StudyState state() {
        return model.state().getValue();
    }

    /** {@code postValue} hands the value to the main looper, which Robolectric runs only on ask. */
    private void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private int immediateSyncRequests() throws Exception {
        List<WorkInfo> enqueued = WorkManager.getInstance(RuntimeEnvironment.getApplication())
                .getWorkInfosForUniqueWork(SyncScheduler.IMMEDIATE_WORK)
                .get();
        return enqueued.size();
    }

    private void cacheCard(long id, LocalDate dueDate) {
        CardEntity card = new CardEntity();
        card.id = id;
        card.serverId = id;
        card.topicId = 1L;
        card.front = "front " + id;
        card.back = "back " + id;
        card.easeFactor = 2.5d;
        card.intervalDays = 1;
        card.repetitions = 1;
        card.dueDate = dueDate;
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
