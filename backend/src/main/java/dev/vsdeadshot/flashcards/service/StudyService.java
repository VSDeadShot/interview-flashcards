package dev.vsdeadshot.flashcards.service;

import dev.vsdeadshot.flashcards.domain.Card;
import dev.vsdeadshot.flashcards.domain.ReviewLog;
import dev.vsdeadshot.flashcards.repository.CardRepository;
import dev.vsdeadshot.flashcards.repository.ReviewLogRepository;
import dev.vsdeadshot.flashcards.scheduler.SchedulingState;
import dev.vsdeadshot.flashcards.scheduler.Sm2Scheduler;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Studying: what to review next, and what a review does to a card.
 *
 * <p>This is the only place the scheduler, the card and the review log meet.
 */
@Service
public class StudyService {

    /** What {@code GET /study/queue} returns when the caller does not say. */
    public static final int DEFAULT_LIMIT = 20;

    /** Upper bound on one queue request. A study session is not a bulk export. */
    public static final int MAX_LIMIT = 100;

    /**
     * How far back a client may date a review it is only now able to send. Long enough for a
     * fortnight with no signal and then some; short enough that a device whose clock is wrong
     * by months is refused rather than believed.
     */
    public static final Duration MAX_BACKDATE = Duration.ofDays(30);

    /**
     * Slack on "not in the future", because a phone's clock is not the server's to the second
     * and rejecting a review for being twelve seconds ahead would be a bug report, not a fix.
     */
    public static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(5);

    private final CardRepository cards;
    private final ReviewLogRepository reviewLogs;
    private final Clock clock;

    /**
     * Constructed directly rather than injected: it is a pure, stateless function object with
     * no dependencies of its own, and keeping it out of the container is what stops anything
     * in {@code scheduler/} from needing a Spring annotation.
     */
    private final Sm2Scheduler scheduler = new Sm2Scheduler();

    public StudyService(CardRepository cards, ReviewLogRepository reviewLogs, Clock clock) {
        this.cards = cards;
        this.reviewLogs = reviewLogs;
        this.clock = clock;
    }

    /**
     * Cards due today or earlier, longest overdue first.
     *
     * @param limit how many to return; clamped to {@link #MAX_LIMIT}
     * @throws IllegalArgumentException if {@code limit} is not positive
     */
    @Transactional(readOnly = true)
    public List<Card> queue(String userId, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1, was " + limit);
        }
        return cards.findStudyQueue(userId, LocalDate.now(clock), Limit.of(Math.min(limit, MAX_LIMIT)));
    }

    /** Reviewed now. The online path, where the server's clock is the review's clock. */
    @Transactional
    public Card review(String userId, long cardId, int confidence) {
        return review(userId, cardId, confidence, null);
    }

    /**
     * Records one review and reschedules the card.
     *
     * <p>A card that is not due yet may still be reviewed. Studying ahead is a legitimate
     * thing to do and the scheduler handles it without special cases — the next interval is
     * measured from the day the review actually happened, not from the day it was due.
     *
     * <p>An archived card is refused as {@code not found}: archiving takes a card out of
     * circulation, and the alternative is a client with a stale queue quietly rescheduling
     * something the user has already retired.
     *
     * @param reviewedAt when the review actually happened, or {@code null} for now. An offline
     *                   client studies on a train and syncs hours or days later; stamping
     *                   arrival time would credit the wrong day, which is exactly what the
     *                   streak counts. The interval runs from this day too, so a review
     *                   recorded three days late can leave the card already due — truthfully,
     *                   because it is.
     * @throws IllegalArgumentException if {@code confidence} is out of range, or
     *                                  {@code reviewedAt} is in the future, older than
     *                                  {@link #MAX_BACKDATE}, or precedes the card's own last
     *                                  review
     * @throws NotFoundException        if the card does not exist, is archived, or belongs to
     *                                  another user
     */
    @Transactional
    public Card review(String userId, long cardId, int confidence, Instant reviewedAt) {
        return review(userId, cardId, confidence, reviewedAt, null);
    }

    /**
     * As above, for a client whose request may already have been applied.
     *
     * <p>The lookup happens <strong>before</strong> the card is loaded and before the ordering
     * check, and that order is the whole point rather than tidiness. A retry sent after a later
     * review has landed carries a {@code reviewedAt} the card has now moved past, so the
     * ordering rule would refuse it as out-of-order — when the truthful answer is that it was
     * already applied. Checking the key first turns that {@code 400} into the {@code 200} it
     * should always have been.
     *
     * <p>The ordering rule does not catch retries on its own either: a retry carries the same
     * instant as the original, which is not <em>before</em> the card's last review, so it would
     * sail through and apply SM-2 a second time. The two mechanisms overlap nowhere.
     *
     * <p>What comes back is the card as it stands now, not as it stood after the original
     * review. If something landed in between, this is the newer schedule — which is both the
     * more useful answer for a client about to re-sync, and impossible to reconstruct anyway:
     * the log keeps {@code interval_after} and {@code ease_factor_after}, but no due date.
     *
     * @param clientReviewId the caller's id for this review, or null to skip deduplication
     * @throws IdempotencyKeyReuseException if the key was already used for a different review
     * @throws ConcurrentRequestException   if an identical request is in flight and won the race
     */
    @Transactional
    public Card review(
            String userId, long cardId, int confidence, Instant reviewedAt, UUID clientReviewId) {
        // Checked before the lookup so a bad confidence reads as a validation failure rather
        // than depending on whether the card happens to exist. The scheduler enforces the same
        // range; these are its constants, not a second copy of the rule.
        if (confidence < Sm2Scheduler.MIN_CONFIDENCE || confidence > Sm2Scheduler.MAX_CONFIDENCE) {
            throw new IllegalArgumentException("confidence must be between "
                    + Sm2Scheduler.MIN_CONFIDENCE + " and " + Sm2Scheduler.MAX_CONFIDENCE
                    + ", was " + confidence);
        }

        Instant now = clock.instant();
        // Truncated to what the column actually stores. Instant carries nanoseconds and
        // timestamptz keeps microseconds, so an untruncated value would not equal the one read
        // back — and a legitimate retry would look like a key reused with a different time.
        Instant happenedAt = reviewedAt == null
                ? now
                : validBackdate(reviewedAt.truncatedTo(ChronoUnit.MICROS), now);

        if (clientReviewId != null) {
            Optional<ReviewLog> already = reviewLogs.findByUserIdAndClientReviewId(userId, clientReviewId);
            if (already.isPresent()) {
                return replayOf(already.get(), userId, cardId, confidence, happenedAt);
            }
        }

        Card card = cards.findByIdAndUserId(cardId, userId)
                .filter(found -> !found.isArchived())
                .orElseThrow(() -> new NotFoundException("card", cardId));

        // A review older than the card's last one would rewind a schedule computed from newer
        // information, and the log would claim a transition that never happened in that order.
        // A single client replaying its queue in order cannot produce this; something that does
        // is confused, and should hear so rather than have SM-2 quietly run backwards.
        if (card.getLastReviewedAt() != null && happenedAt.isBefore(card.getLastReviewedAt())) {
            throw new IllegalArgumentException("reviewedAt " + happenedAt
                    + " is before this card's last review at " + card.getLastReviewedAt());
        }

        // Captured before applySchedule overwrites the card's columns. This is the ordering
        // ReviewLog.of exists to survive: a moment later the "before" values are gone.
        SchedulingState before = card.schedulingState();
        SchedulingState after = scheduler.schedule(
                before, confidence, LocalDate.ofInstant(happenedAt, clock.getZone()));

        card.applySchedule(after, happenedAt);
        try {
            reviewLogs.saveAndFlush(
                    ReviewLog.of(card, confidence, before, after, happenedAt, clientReviewId));
        } catch (DataIntegrityViolationException e) {
            // Only reachable when a request with this key committed between the lookup above
            // and this insert. Named rather than assumed, so a future constraint on review_log
            // cannot be reported as a duplicated request.
            if (!Constraints.isViolationOf("uq_review_log_client_id", e)) {
                throw e;
            }
            throw new ConcurrentRequestException(
                    "a review with clientReviewId " + clientReviewId + " is already in progress");
        }
        return card;
    }

    /**
     * The answer to a request that was already applied. The payload is compared first, because
     * a key that names one review arriving with the details of another is a client that has
     * lost track of its own queue, and answering it with somebody else's outcome would record
     * the wrong review under the right key permanently.
     */
    private Card replayOf(
            ReviewLog original, String userId, long cardId, int confidence, Instant happenedAt) {
        long originalCardId = original.getCard().getId();
        if (originalCardId != cardId
                || original.getConfidence() != confidence
                || !original.getReviewedAt().equals(happenedAt)) {
            throw new IdempotencyKeyReuseException("clientReviewId "
                    + original.getClientReviewId() + " was already used for a different review");
        }
        // Re-read rather than navigating original.getCard(): that is a lazy proxy, and the
        // caller serialises this outside the transaction.
        return cards.findByIdAndUserId(originalCardId, userId)
                .orElseThrow(() -> new NotFoundException("card", originalCardId));
    }

    private static Instant validBackdate(Instant reviewedAt, Instant now) {
        if (reviewedAt.isAfter(now.plus(FUTURE_TOLERANCE))) {
            throw new IllegalArgumentException(
                    "reviewedAt must not be in the future, was " + reviewedAt);
        }
        if (reviewedAt.isBefore(now.minus(MAX_BACKDATE))) {
            throw new IllegalArgumentException("reviewedAt must be within the last "
                    + MAX_BACKDATE.toDays() + " days, was " + reviewedAt);
        }
        return reviewedAt;
    }
}
