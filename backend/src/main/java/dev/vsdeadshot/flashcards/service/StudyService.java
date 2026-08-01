package dev.vsdeadshot.flashcards.service;

import dev.vsdeadshot.flashcards.domain.Card;
import dev.vsdeadshot.flashcards.domain.ReviewLog;
import dev.vsdeadshot.flashcards.repository.CardRepository;
import dev.vsdeadshot.flashcards.repository.ReviewLogRepository;
import dev.vsdeadshot.flashcards.scheduler.SchedulingState;
import dev.vsdeadshot.flashcards.scheduler.Sm2Scheduler;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
     * @throws IllegalArgumentException if {@code confidence} is out of range
     * @throws NotFoundException        if the card does not exist, is archived, or belongs to
     *                                  another user
     */
    @Transactional
    public Card review(String userId, long cardId, int confidence) {
        // Checked before the lookup so a bad confidence reads as a validation failure rather
        // than depending on whether the card happens to exist. The scheduler enforces the same
        // range; these are its constants, not a second copy of the rule.
        if (confidence < Sm2Scheduler.MIN_CONFIDENCE || confidence > Sm2Scheduler.MAX_CONFIDENCE) {
            throw new IllegalArgumentException("confidence must be between "
                    + Sm2Scheduler.MIN_CONFIDENCE + " and " + Sm2Scheduler.MAX_CONFIDENCE
                    + ", was " + confidence);
        }

        Card card = cards.findByIdAndUserId(cardId, userId)
                .filter(found -> !found.isArchived())
                .orElseThrow(() -> new NotFoundException("card", cardId));

        Instant reviewedAt = clock.instant();
        // Captured before applySchedule overwrites the card's columns. This is the ordering
        // ReviewLog.of exists to survive: a moment later the "before" values are gone.
        SchedulingState before = card.schedulingState();
        SchedulingState after = scheduler.schedule(before, confidence, LocalDate.now(clock));

        card.applySchedule(after, reviewedAt);
        reviewLogs.save(ReviewLog.of(card, confidence, before, after, reviewedAt));
        return card;
    }
}
