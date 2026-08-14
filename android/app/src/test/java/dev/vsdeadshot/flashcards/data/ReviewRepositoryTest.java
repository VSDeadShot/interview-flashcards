package dev.vsdeadshot.flashcards.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import androidx.room.Room;
import dev.vsdeadshot.flashcards.data.local.CardEntity;
import dev.vsdeadshot.flashcards.data.local.FlashcardsDatabase;
import dev.vsdeadshot.flashcards.data.local.PendingReviewEntity;
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

/**
 * Recording a review with the radio off.
 *
 * <p>The clock is fixed, and deliberately not today's date, so a date assertion below cannot
 * pass by coincidence on the day it happens to run — the same reasoning as the backend's
 * {@code FixedClockConfiguration}.
 *
 * <p>The expected schedules are not restated arithmetic: they are what {@code Sm2SchedulerTest}
 * already pins, checked here to prove this class runs the real scheduler over the card's real
 * state rather than approximating either.
 */
@RunWith(RobolectricTestRunner.class)
public class ReviewRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-03-17T21:30:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 17);

    private FlashcardsDatabase db;
    private ReviewRepository reviews;

    @Before
    public void setUp() {
        db = Room.inMemoryDatabaseBuilder(
                        RuntimeEnvironment.getApplication(), FlashcardsDatabase.class)
                .allowMainThreadQueries()
                .build();
        reviews = new ReviewRepository(db, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @After
    public void tearDown() {
        db.close();
    }

    /** A card one successful review into its run: the next one is the 6-day step. */
    private void cacheCard(long id) {
        CardEntity card = new CardEntity();
        card.id = id;
        card.topicId = 1L;
        card.front = "What is a deadlock?";
        card.back = "Four Coffman conditions";
        card.easeFactor = 2.5d;
        card.intervalDays = 1;
        card.repetitions = 1;
        card.lapses = 0;
        card.dueDate = TODAY;
        db.cards().upsertAll(List.of(card));
    }

    @Test
    public void aReviewIsQueuedAndTheCardAdvancesInOneGo() {
        cacheCard(1L);

        CardEntity updated = reviews.record(1L, 5);

        assertEquals("exactly one review is outstanding", 1, db.pendingReviews().size());
        PendingReviewEntity queued = db.pendingReviews().queued().get(0);
        assertEquals(1L, queued.cardId);
        assertEquals(5, queued.confidence);
        assertEquals("the card returned is the one that was written",
                6, db.cards().findById(1L).intervalDays);
        assertEquals(6, updated.intervalDays);
    }

    @Test
    public void theCardTakesTheScheduleTheServerWillComputeForItself() {
        cacheCard(1L);

        reviews.record(1L, 5);

        CardEntity card = db.cards().findById(1L);
        assertEquals("a second success is the 6-day step", 6, card.intervalDays);
        assertEquals(2, card.repetitions);
        assertEquals("a perfect recall raises the ease by 0.1", 2.6d, card.easeFactor, 1e-9);
        assertEquals(LocalDate.of(2026, 3, 23), card.dueDate);
        assertEquals(NOW, card.lastReviewedAt);
    }

    @Test
    public void aLapseResetsTheRunAndCountsAgainstTheCard() {
        cacheCard(1L);

        reviews.record(1L, 2);

        CardEntity card = db.cards().findById(1L);
        assertEquals("back to the start of the ladder", 0, card.repetitions);
        assertEquals(1, card.intervalDays);
        assertEquals(1, card.lapses);
        assertEquals(LocalDate.of(2026, 3, 18), card.dueDate);
        assertEquals(
                "a lapse deliberately does not reduce the ease factor — one bad day should not"
                        + " permanently degrade the card",
                2.5d,
                card.easeFactor,
                1e-9);
    }

    @Test
    public void theOutboxRowCarriesWhenTheUserActuallyAnswered() {
        cacheCard(1L);

        reviews.record(1L, 4);

        assertEquals(
                "sent as-is, so a review synced days later still counts for the day it happened"
                        + " and does not break the streak",
                NOW,
                db.pendingReviews().queued().get(0).reviewedAt);
    }

    @Test
    public void everyReviewGetsItsOwnIdempotencyKey() {
        cacheCard(1L);

        reviews.record(1L, 4);
        reviews.record(1L, 5);

        List<PendingReviewEntity> queued = db.pendingReviews().queued();
        assertEquals(2, queued.size());
        assertNotEquals(
                "two answers are two reviews — sharing a key would have the server recognise the"
                        + " second as a replay of the first and discard it",
                queued.get(0).clientReviewId,
                queued.get(1).clientReviewId);
    }

    @Test
    public void aCardThatIsNotCachedIsRefusedAndNothingIsWritten() {
        assertThrows(IllegalArgumentException.class, () -> reviews.record(404L, 4));

        assertEquals(0, db.pendingReviews().size());
    }

    @Test
    public void aConfidenceOutsideTheScaleIsRefusedBeforeAnythingIsQueued() {
        cacheCard(1L);

        assertThrows(IllegalArgumentException.class, () -> reviews.record(1L, 6));

        assertTrue("nothing to send", db.pendingReviews().queued().isEmpty());
        assertEquals("and the card is untouched", 1, db.cards().findById(1L).intervalDays);
    }
}
