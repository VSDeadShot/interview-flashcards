package dev.vsdeadshot.flashcards.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import androidx.room.Room;
import dev.vsdeadshot.flashcards.data.StudyRepository.StudyView;
import dev.vsdeadshot.flashcards.data.local.CardEntity;
import dev.vsdeadshot.flashcards.data.local.FlashcardsDatabase;
import dev.vsdeadshot.flashcards.data.local.TopicEntity;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Which card comes next, and what is said when none does.
 *
 * <p>The ordering matters beyond tidiness: it is the ordering the server's own queue uses, so a
 * session studied offline is the session that would have been handed out online.
 *
 * <p><strong>What the ordering tests here cannot tell you</strong> is that the SQL asks for that
 * order. Deleting {@code order by dueDate asc, id asc} from {@code CardDao.queue} leaves every one
 * of them passing, because {@code index_card_archived_dueDate_id} covers the predicate and an
 * index scan hands rows back in index order anyway — which is the same order. So these assert
 * which card comes out, which is the thing that matters to somebody studying; the {@code order by}
 * stays in the query because a planner that stopped using that index would otherwise change the
 * answer with nothing here to notice.
 */
@RunWith(RobolectricTestRunner.class)
// FlashcardsApp is kept out of this: Robolectric does not create the app's content providers, so
// androidx.startup never initialises WorkManager and onCreate's call to it throws.
@Config(application = Application.class)
public class StudyRepositoryTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 17);
    private static final Clock FIXED =
            Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    private FlashcardsDatabase db;
    private StudyRepository study;
    private ReviewRepository reviews;

    @Before
    public void setUp() {
        db = Room.inMemoryDatabaseBuilder(
                        RuntimeEnvironment.getApplication(), FlashcardsDatabase.class)
                .allowMainThreadQueries()
                .build();
        study = new StudyRepository(db, FIXED);
        reviews = new ReviewRepository(db, FIXED);
        cacheTopic(1L, "Operating Systems");
    }

    @After
    public void tearDown() {
        db.close();
    }

    // ---- which card ---------------------------------------------------------------------------

    @Test
    public void theCardDueLongestIsTheOneHandedOut() {
        cacheCard(1L, TODAY, false);
        cacheCard(2L, TODAY.minusDays(3), false);
        cacheCard(3L, TODAY.minusDays(1), false);

        StudyView view = study.next();

        assertEquals("a card three days overdue is more overdue than one due today",
                2L, view.card().id);
    }

    /**
     * The tiebreak the server also applies: two cards due the same day could otherwise swap places
     * between reads, and a queue that reorders itself is one nobody can work through. See the note
     * on this class about what this does and does not pin.
     */
    @Test
    public void cardsDueTheSameDayAreBrokenByIdSoTheOrderIsStable() {
        cacheCard(7L, TODAY, false);
        cacheCard(3L, TODAY, false);

        assertEquals(3L, study.next().card().id);
    }

    /**
     * Local ids run downwards from zero, so a card written on this device sorts ahead of every
     * card the server sent for the same day. There is nothing to disagree with: the server has no
     * opinion about a card it has not seen.
     */
    @Test
    public void aCardWrittenHereComesFirstAmongTheCardsDueThatDay() {
        cacheCard(1L, TODAY, false);
        CardEntity written = new CardRepository(db, FIXED).create(1L, "written here", "back");

        assertEquals(written.id, study.next().card().id);
    }

    @Test
    public void aCardDueLaterIsNotDueNow() {
        cacheCard(1L, TODAY.plusDays(1), false);

        StudyView view = study.next();

        assertFalse("tomorrow is not today, however close it is", view.hasCard());
        assertEquals(0, view.dueCount());
        assertEquals("but it is still a card somebody has", 1, view.totalCards());
    }

    @Test
    public void anArchivedCardIsOutOfCirculationAndOutOfTheQueue() {
        cacheCard(1L, TODAY, true);

        StudyView view = study.next();

        assertFalse(view.hasCard());
        assertEquals(0, view.totalCards());
    }

    /** The loop this screen runs on: answering the head hands out the next one, not the same one. */
    @Test
    public void answeringTheHeadHandsOutTheNextCard() {
        cacheCard(1L, TODAY, false);
        cacheCard(2L, TODAY, false);
        assertEquals(1L, study.next().card().id);

        reviews.record(1L, 5);

        assertEquals("every schedule the scheduler produces is at least a day out, so an answered"
                + " card cannot come back today", 2L, study.next().card().id);
    }

    @Test
    public void aCardAnsweredBadlyStillLeavesTheQueueForToday() {
        cacheCard(1L, TODAY, false);

        reviews.record(1L, 1);

        assertFalse("a lapse goes back to the start of the ladder tomorrow, not to the front of"
                + " the queue for today", study.next().hasCard());
    }

    // ---- what is shown beside it --------------------------------------------------------------

    @Test
    public void theCardCarriesItsTopicName() {
        cacheCard(1L, TODAY, false);

        assertEquals("Operating Systems", study.next().topicName());
    }

    /**
     * A pull can write a card before its topic, and a topic can be deleted on the server. Neither
     * is a reason to withhold the question.
     */
    @Test
    public void aCardWhoseTopicIsNotCachedIsStillHandedOut() {
        cacheCardInTopic(1L, 99L, TODAY, false);

        StudyView view = study.next();

        assertEquals(1L, view.card().id);
        assertNull(view.topicName());
    }

    @Test
    public void theDueCountIsEverythingOwedTodayNotJustThisCard() {
        cacheCard(1L, TODAY, false);
        cacheCard(2L, TODAY.minusDays(2), false);
        cacheCard(3L, TODAY.plusDays(5), false);

        StudyView view = study.next();

        assertEquals(2, view.dueCount());
        assertEquals(3, view.totalCards());
    }

    // ---- the two empties ----------------------------------------------------------------------

    /**
     * The distinction this record exists to draw. Congratulating somebody on finishing a queue
     * they have never had a card in also hides the reason there is nothing there.
     */
    @Test
    public void anEmptyCacheIsNotTheSameAsACaughtUpOne() {
        StudyView empty = study.next();

        assertFalse(empty.hasCard());
        assertTrue("nothing has ever been here", empty.isEmptyCache());
    }

    @Test
    public void aCaughtUpCacheIsNotReportedAsEmpty() {
        cacheCard(1L, TODAY, false);
        reviews.record(1L, 4);

        StudyView caughtUp = study.next();

        assertFalse(caughtUp.hasCard());
        assertFalse("there is a card, it is just not due", caughtUp.isEmptyCache());
        assertEquals(1, caughtUp.totalCards());
    }

    // ---- fixtures -----------------------------------------------------------------------------

    private void cacheCard(long id, LocalDate dueDate, boolean archived) {
        cacheCardInTopic(id, 1L, dueDate, archived);
    }

    private void cacheCardInTopic(long id, long topicId, LocalDate dueDate, boolean archived) {
        CardEntity card = new CardEntity();
        card.id = id;
        card.serverId = id;
        card.topicId = topicId;
        card.front = "front " + id;
        card.back = "back " + id;
        card.easeFactor = 2.5d;
        card.intervalDays = 1;
        card.repetitions = 1;
        card.dueDate = dueDate;
        card.archived = archived;
        db.cards().upsertAll(List.of(card));
    }

    private void cacheTopic(long id, String name) {
        TopicEntity topic = new TopicEntity();
        topic.id = id;
        topic.name = name;
        topic.slug = name.toLowerCase().replace(' ', '-');
        topic.createdAt = Instant.parse("2026-01-01T00:00:00Z");
        db.topics().upsertAll(List.of(topic));
    }
}
