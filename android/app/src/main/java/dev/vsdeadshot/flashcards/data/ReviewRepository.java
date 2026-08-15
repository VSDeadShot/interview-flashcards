package dev.vsdeadshot.flashcards.data;

import dev.vsdeadshot.flashcards.data.local.CardEntity;
import dev.vsdeadshot.flashcards.data.local.FlashcardsDatabase;
import dev.vsdeadshot.flashcards.data.local.PendingReviewEntity;
import dev.vsdeadshot.flashcards.scheduler.SchedulingState;
import dev.vsdeadshot.flashcards.scheduler.Sm2Scheduler;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Records a review the user just performed, without needing the network.
 *
 * <p>Two writes, in one transaction, in this order: the outbox row, then the card. Both parts
 * matter.
 *
 * <p>The order is the documented rule that the outbox is written before the card it belongs to
 * is touched. The transaction is why the rule can be relied on — the two failure modes are not
 * symmetric. A card advanced with no outbox row loses the user's answer silently and hides the
 * card for days; an outbox row with an unadvanced card is repaired by the next sync, which
 * sends the review and writes the server's schedule back. Only one of those is worth guarding
 * against, and a transaction guards against it completely.
 *
 * <p>The new schedule written here is a prediction, produced by the same SM-2 the server runs.
 * It is what the UI shows until the review is accepted, at which point {@code SyncEngine}
 * replaces it with the server's answer.
 */
public final class ReviewRepository {

    private final FlashcardsDatabase db;
    private final Sm2Scheduler scheduler = new Sm2Scheduler();
    private final Clock clock;

    public ReviewRepository(FlashcardsDatabase db) {
        this(db, Clock.systemDefaultZone());
    }

    /**
     * @param clock the device's clock in production. Injected because the day a review lands on
     *     is the one thing here that cannot be asserted on otherwise — the scheduler itself
     *     takes the date as a parameter precisely so it never needs one.
     */
    public ReviewRepository(FlashcardsDatabase db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    /**
     * @param confidence how well the card was recalled, {@value Sm2Scheduler#MIN_CONFIDENCE}–{@value
     *     Sm2Scheduler#MAX_CONFIDENCE}
     * @return the card as it now stands, so the caller can show the predicted schedule
     * @throws IllegalArgumentException if the card is not cached, or the confidence is out of
     *     range — in either case nothing is written
     */
    public CardEntity record(long cardId, int confidence) {
        Instant reviewedAt = clock.instant();
        // The user's local day, not UTC: a card answered at 11pm counts for the day the user
        // thinks they answered it, which is also the day the server's streak will credit.
        LocalDate today = LocalDate.now(clock);
        // Generated once, here, rather than when the review is sent. Every retry of this row
        // then carries the same value, which is what makes a lost response safe.
        UUID clientReviewId = UUID.randomUUID();

        return db.runInTransaction(() -> {
            // Read inside the transaction so a pull landing at the same moment cannot slip
            // between the read and the write and have its row overwritten by this prediction.
            CardEntity card = db.cards().findById(cardId);
            if (card == null) {
                throw new IllegalArgumentException("No cached card with id " + cardId);
            }
            // Before either write, so a rejected confidence leaves the transaction with nothing
            // to roll back. The scheduler's own message names the offending value.
            SchedulingState next = scheduler.schedule(card.schedulingState(), confidence, today);

            PendingReviewEntity review = new PendingReviewEntity();
            review.cardId = cardId;
            review.confidence = confidence;
            review.reviewedAt = reviewedAt;
            review.clientReviewId = clientReviewId;
            db.pendingReviews().enqueue(review);

            card.applySchedule(next, reviewedAt);
            db.cards().update(card);
            // In the same transaction as the outbox row, so the count on a stats screen can
            // never disagree with what was actually queued.
            db.stats().recordReview(today);
            return card;
        });
    }
}
