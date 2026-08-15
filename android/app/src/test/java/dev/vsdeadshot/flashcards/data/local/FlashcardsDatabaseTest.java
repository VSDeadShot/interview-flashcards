package dev.vsdeadshot.flashcards.data.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import androidx.room.Room;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Runs against a real SQLite database, in memory, on the JVM — no emulator and no device, for
 * the same reason the backend runs a real PostgreSQL in-process rather than asking for Docker.
 * Room generates its queries at build time, so a DAO that does not compile is caught by the
 * build; what these prove is that the queries return what the app expects once they do.
 */
@RunWith(RobolectricTestRunner.class)
// FlashcardsApp is kept out of this: Robolectric does not create the app's content
// providers, so androidx.startup never initialises WorkManager and onCreate's call to it
// throws. A data-layer test has no business running the app's startup wiring anyway.
@Config(application = Application.class)
public class FlashcardsDatabaseTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 17);

    private FlashcardsDatabase db;
    private CardDao cards;
    private TopicDao topics;
    private PendingReviewDao outbox;

    @Before
    public void openDatabase() {
        db = Room.inMemoryDatabaseBuilder(
                        RuntimeEnvironment.getApplication(), FlashcardsDatabase.class)
                .allowMainThreadQueries()
                .build();
        cards = db.cards();
        topics = db.topics();
        outbox = db.pendingReviews();
    }

    @After
    public void closeDatabase() {
        db.close();
    }

    private CardEntity card(long id, LocalDate dueDate, boolean archived) {
        CardEntity card = new CardEntity();
        card.id = id;
        card.topicId = 1L;
        card.front = "front " + id;
        card.back = "back " + id;
        card.easeFactor = 2.5d;
        card.dueDate = dueDate;
        card.archived = archived;
        return card;
    }

    @Test
    public void aCardSurvivesTheRoundTripWithItsSchedule() {
        CardEntity written = card(1L, TODAY, false);
        written.intervalDays = 6;
        written.repetitions = 2;
        written.lapses = 1;
        written.lastReviewedAt = Instant.parse("2026-03-11T09:15:00Z");
        cards.upsertAll(List.of(written));

        CardEntity read = cards.findById(1L);

        assertEquals(6, read.intervalDays);
        assertEquals(2, read.repetitions);
        assertEquals(1, read.lapses);
        assertEquals(2.5d, read.easeFactor, 1e-9);
        assertEquals(TODAY, read.dueDate);
        assertEquals(written.lastReviewedAt, read.lastReviewedAt);
    }

    @Test
    public void aNeverReviewedCardKeepsANullTimestamp() {
        cards.upsertAll(List.of(card(1L, TODAY, false)));

        assertNull("null means never reviewed, and must not become the epoch",
                cards.findById(1L).lastReviewedAt);
    }

    @Test
    public void theQueueOffersDueCardsOldestFirstAndSkipsTheRest() {
        cards.upsertAll(List.of(
                card(3L, TODAY, false),
                card(1L, TODAY.minusDays(2), false),
                card(2L, TODAY.plusDays(1), false),
                card(4L, TODAY.minusDays(5), true)));

        List<Long> queue = cards.queue(TODAY, 20).stream().map(c -> c.id).toList();

        assertEquals("longest overdue first, then today's; not-yet-due and archived are out",
                List.of(1L, 3L), queue);
    }

    @Test
    public void theQueueHonoursItsLimit() {
        cards.upsertAll(List.of(card(1L, TODAY, false), card(2L, TODAY, false)));

        assertEquals(1, cards.queue(TODAY, 1).size());
    }

    @Test
    public void aPullRemovesCardsTheServerNoLongerLists() {
        cards.upsertAll(List.of(card(1L, TODAY, false), card(2L, TODAY, false)));

        cards.deleteMissing(List.of(1L));

        assertEquals(1, cards.findAllActive().size());
        assertNull(cards.findById(2L));
    }

    @Test
    public void anUpsertOverwritesTheCardTheServerReturned() {
        cards.upsertAll(List.of(card(1L, TODAY, false)));
        CardEntity rescheduled = card(1L, TODAY.plusDays(6), false);
        rescheduled.intervalDays = 6;

        cards.upsertAll(List.of(rescheduled));

        assertEquals("a pull is a replacement, not a merge", 6, cards.findById(1L).intervalDays);
        assertEquals(TODAY.plusDays(6), cards.findById(1L).dueDate);
    }

    @Test
    public void theOutboxReplaysInTheOrderItWasWritten() {
        outbox.enqueue(pendingReview(1L, 5));
        outbox.enqueue(pendingReview(2L, 3));

        List<PendingReviewEntity> queued = outbox.queued();

        assertEquals(2, queued.size());
        assertTrue("oldest first, because the server refuses a review older than the last one",
                queued.get(0).id < queued.get(1).id);
        assertEquals(1L, queued.get(0).cardId);
    }

    @Test
    public void theOutboxNamesTheCardsAPullMustNotOverwrite() {
        outbox.enqueue(pendingReview(7L, 5));
        outbox.enqueue(pendingReview(7L, 4));

        assertEquals("one card, however many reviews are waiting on it",
                List.of(7L), outbox.cardIdsAwaitingSync());
    }

    @Test
    public void aSentReviewLeavesTheOutbox() {
        outbox.enqueue(pendingReview(1L, 5));

        outbox.delete(outbox.queued().get(0));

        assertEquals(0, outbox.size());
    }

    @Test
    public void aFailedAttemptIsCountedAndExplained() {
        outbox.enqueue(pendingReview(1L, 5));
        PendingReviewEntity queued = outbox.queued().get(0);

        outbox.recordFailure(queued.id, "connection reset");

        PendingReviewEntity after = outbox.queued().get(0);
        assertEquals(1, after.attempts);
        assertEquals("a stuck queue should be able to say why", "connection reset", after.lastError);
    }

    @Test
    public void aTopicSurvivesTheRoundTrip() {
        TopicEntity topic = new TopicEntity();
        topic.id = 3L;
        topic.name = "Operating Systems";
        topic.slug = "operating-systems";
        topic.createdAt = Instant.parse("2026-07-01T08:00:00Z");
        topics.upsertAll(List.of(topic));

        assertEquals("Operating Systems", topics.findById(3L).name);
        assertEquals(topic.createdAt, topics.findById(3L).createdAt);
    }

    private PendingReviewEntity pendingReview(long cardId, int confidence) {
        PendingReviewEntity review = new PendingReviewEntity();
        review.cardId = cardId;
        review.confidence = confidence;
        review.reviewedAt = Instant.parse("2026-03-17T09:00:00Z");
        review.clientReviewId = UUID.randomUUID();
        return review;
    }
}
