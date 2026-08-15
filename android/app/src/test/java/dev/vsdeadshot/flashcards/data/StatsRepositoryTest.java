package dev.vsdeadshot.flashcards.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import androidx.room.Room;
import dev.vsdeadshot.flashcards.data.StatsRepository.StatsView;
import dev.vsdeadshot.flashcards.data.local.CardEntity;
import dev.vsdeadshot.flashcards.data.local.FlashcardsDatabase;
import dev.vsdeadshot.flashcards.data.local.StatsSnapshotEntity;
import dev.vsdeadshot.flashcards.data.local.TopicEntity;
import dev.vsdeadshot.flashcards.data.local.TopicStatsRow;
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
 * The figures a stats screen reads, all of them from the cache except one.
 *
 * <p>The distinction these are mostly about is which numbers are counted now and which are
 * remembered. Counting now is what lets the due figure fall while somebody studies, and what
 * makes cards written on this device count before the server has heard of them.
 */
@RunWith(RobolectricTestRunner.class)
// FlashcardsApp is kept out of this: Robolectric does not create the app's content
// providers, so androidx.startup never initialises WorkManager and onCreate's call to it
// throws. A data-layer test has no business running the app's startup wiring anyway.
@Config(application = Application.class)
public class StatsRepositoryTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 17);
    private static final Clock FIXED =
            Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    private FlashcardsDatabase db;
    private StatsRepository stats;
    private ReviewRepository reviews;
    private CardRepository cards;

    @Before
    public void setUp() {
        db = Room.inMemoryDatabaseBuilder(
                        RuntimeEnvironment.getApplication(), FlashcardsDatabase.class)
                .allowMainThreadQueries()
                .build();
        stats = new StatsRepository(db, FIXED);
        reviews = new ReviewRepository(db, FIXED);
        cards = new CardRepository(db, FIXED);
        cacheTopic(1L, "Operating Systems");
    }

    @After
    public void tearDown() {
        db.close();
    }

    // ---- counted from the cache -------------------------------------------------------------

    @Test
    public void archivedCardsAreOutOfCirculationAndOutOfTheCount() {
        cacheCard(1L, TODAY, false);
        cacheCard(2L, TODAY, true);

        StatsView view = stats.snapshot();

        assertEquals("an archived card is out of the app, so it is not a card you have", 1,
                view.totalCards());
        assertEquals("nor one you owe", 1, view.dueToday());
    }

    @Test
    public void aCardDueLaterIsCountedAsHeldButNotAsDue() {
        cacheCard(1L, TODAY, false);
        cacheCard(2L, TODAY.plusDays(3), false);

        StatsView view = stats.snapshot();

        assertEquals(2, view.totalCards());
        assertEquals(1, view.dueToday());
    }

    /**
     * The reason these are counted rather than remembered. A figure taken from the server's last
     * answer would sit still while somebody worked through their queue.
     */
    @Test
    public void theDueCountFallsAsCardsAreAnswered() {
        cacheCard(1L, TODAY, false);
        cacheCard(2L, TODAY, false);
        assertEquals(2, stats.snapshot().dueToday());

        reviews.record(1L, 5);

        assertEquals("answering a card takes it out of what is owed today",
                1, stats.snapshot().dueToday());
    }

    @Test
    public void aCardWrittenHereCountsBeforeTheServerHasHeardOfIt() {
        cards.create(1L, "written here", "back");

        StatsView view = stats.snapshot();

        assertEquals("the cache is the more current source, not a stale copy of the server",
                1, view.totalCards());
        assertEquals("and a new card is due at once, so it can be studied now", 1,
                view.dueToday());
    }

    // ---- reviewed today ---------------------------------------------------------------------

    @Test
    public void reviewsAnsweredTodayAreCountedEvenBeforeTheyAreSent() {
        cacheCard(1L, TODAY, false);
        cacheCard(2L, TODAY, false);

        reviews.record(1L, 5);
        reviews.record(2L, 3);

        assertEquals("the outbox is emptied by syncing, so the tally cannot be read from it",
                2, stats.snapshot().reviewedToday());
    }

    /**
     * Why the tally is its own count rather than cards whose {@code lastReviewedAt} is today: a
     * card answered twice in one day is one card, and counting rows would undercount exactly on
     * the day somebody worked hardest.
     */
    @Test
    public void answeringOneCardTwiceCountsTwice() {
        cacheCard(1L, TODAY, false);

        reviews.record(1L, 1);
        reviews.record(1L, 4);

        assertEquals(2, stats.snapshot().reviewedToday());
    }

    @Test
    public void yesterdaysReviewsAreNotTodays() {
        cacheCard(1L, TODAY, false);
        new ReviewRepository(db, Clock.fixed(
                TODAY.minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC))
                .record(1L, 4);

        assertEquals("the tally is per day, so a new day starts at nothing",
                0, stats.snapshot().reviewedToday());
    }

    // ---- the streak -------------------------------------------------------------------------

    /**
     * Null and zero are different answers. A screen showing a confident zero to somebody thirty
     * days into a run would be the worst thing this feature could do, so the absence is
     * representable rather than defaulted away.
     */
    @Test
    public void aStreakNeverFetchedIsAbsentRatherThanZero() {
        StatsView view = stats.snapshot();

        assertFalse("nothing has told this client what the streak is", view.hasStreak());
        assertNull(view.streakDays());
        assertNull("so there is no 'as of' to show either", view.streakAsOf());
    }

    @Test
    public void aFetchedStreakIsShownWithWhenItWasFetched() {
        Instant fetchedAt = Instant.parse("2026-03-17T08:15:00Z");
        saveStreak(12, fetchedAt);

        StatsView view = stats.snapshot();

        assertTrue(view.hasStreak());
        assertEquals(Integer.valueOf(12), view.streakDays());
        assertEquals("a streak with no 'as of' claims to be current, and this one is only ever"
                + " as current as the last sync", fetchedAt, view.streakAsOf());
    }

    @Test
    public void onlyTheLatestStreakIsKept() {
        saveStreak(12, Instant.parse("2026-03-16T08:00:00Z"));
        saveStreak(13, Instant.parse("2026-03-17T08:00:00Z"));

        assertEquals("there is one user and one server, so there is one row",
                Integer.valueOf(13), stats.snapshot().streakDays());
    }

    // ---- by topic ---------------------------------------------------------------------------

    @Test
    public void everyTopicAppearsIncludingOneHoldingNoCards() {
        cacheTopic(2L, "Databases");
        cacheCard(1L, TODAY, false);

        List<TopicStatsRow> byTopic = stats.snapshot().byTopic();

        assertEquals(2, byTopic.size());
        assertEquals("ordered by name, so a list does not reshuffle as ids are handed out",
                "Databases", byTopic.get(0).name);
        assertEquals("a topic whose last card was archived would look deleted otherwise",
                0, byTopic.get(0).total);
        assertEquals("Operating Systems", byTopic.get(1).name);
        assertEquals(1, byTopic.get(1).total);
        assertEquals(1, byTopic.get(1).due);
    }

    @Test
    public void aTopicCountsOnlyItsOwnCardsAndOnlyTheLiveOnes() {
        cacheTopic(2L, "Databases");
        cacheCard(1L, TODAY, false);
        cacheCard(2L, TODAY, true);
        cacheCardInTopic(3L, 2L, TODAY.plusDays(5), false);

        List<TopicStatsRow> byTopic = stats.snapshot().byTopic();

        assertEquals("Databases holds one card, not yet due", 1, byTopic.get(0).total);
        assertEquals(0, byTopic.get(0).due);
        assertEquals("Operating Systems holds one, the archived one not counting",
                1, byTopic.get(1).total);
    }

    // ---- fixtures ---------------------------------------------------------------------------

    private void saveStreak(int days, Instant fetchedAt) {
        StatsSnapshotEntity snapshot = new StatsSnapshotEntity();
        snapshot.currentStreakDays = days;
        snapshot.fetchedAt = fetchedAt;
        db.stats().saveSnapshot(snapshot);
    }

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
