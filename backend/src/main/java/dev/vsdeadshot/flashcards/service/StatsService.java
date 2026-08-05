package dev.vsdeadshot.flashcards.service;

import dev.vsdeadshot.flashcards.repository.CardRepository;
import dev.vsdeadshot.flashcards.repository.ReviewLogRepository;
import dev.vsdeadshot.flashcards.repository.TopicRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reporting: how much there is to do, how much was done, and how long the habit has held.
 *
 * <p>Everything here reads. Nothing in this class writes a row or touches a schedule — the
 * numbers come from {@code card}'s own columns and from {@code review_log}, which exists for
 * exactly this.
 *
 * <h2>What the streak counts</h2>
 *
 * <p>Consecutive days with at least one review, walking backwards from today, with two rules
 * that are decisions rather than arithmetic:
 *
 * <ul>
 *   <li><strong>A day on which nothing was due is skipped, not counted and not a miss.</strong>
 *       The scheduler decides when cards come back; a day it gave the user nothing to do
 *       cannot reasonably be held against them. The streak runs across such a day rather than
 *       ending at it.
 *   <li><strong>Today never breaks the streak.</strong> The day is not over. Someone opening
 *       the app at breakfast should see the streak they went to bed with, not a zero that
 *       repairs itself once they study.
 * </ul>
 *
 * <p>This costs two queries per day walked — one asking whether the day was studied, one
 * asking whether anything was due — and the second is only asked for days that were not
 * studied. That is the price of the forgiving rule and it is paid deliberately: the cheap
 * version, counting only days with reviews, would tell a user who studied everything they had
 * that they had broken their streak.
 *
 * <p>The walk terminates at the day of the user's first review. Only studied days are ever
 * counted, so nothing before that can add to the streak — and without a floor the forgiving
 * rule would walk backwards forever through days on which nothing was due.
 */
@Service
public class StatsService {

    private final CardRepository cards;
    private final ReviewLogRepository reviewLogs;
    private final TopicRepository topics;
    private final Clock clock;

    public StatsService(
            CardRepository cards,
            ReviewLogRepository reviewLogs,
            TopicRepository topics,
            Clock clock) {
        this.cards = cards;
        this.reviewLogs = reviewLogs;
        this.topics = topics;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Stats forUser(String userId) {
        LocalDate today = LocalDate.now(clock);
        List<TopicStats> byTopic = topics.findTopicStats(userId, today).stream()
                .map(row -> new TopicStats(
                        row.getTopicId(), row.getName(), row.getTotal(), row.getDue()))
                .toList();

        return new Stats(
                cards.countByUserIdAndArchivedFalse(userId),
                cards.countByUserIdAndArchivedFalseAndDueDateLessThanEqual(userId, today),
                reviewsOn(userId, today),
                currentStreakDays(userId, today),
                byTopic);
    }

    private int currentStreakDays(String userId, LocalDate today) {
        Optional<Instant> firstReview = reviewLogs.findEarliestReviewedAt(userId);
        if (firstReview.isEmpty()) {
            return 0;
        }
        LocalDate floor = LocalDate.ofInstant(firstReview.get(), clock.getZone());

        int streak = 0;
        for (LocalDate day = today; !day.isBefore(floor); day = day.minusDays(1)) {
            if (reviewsOn(userId, day) > 0) {
                streak++;
            } else if (!day.equals(today) && anythingWasDueOn(userId, day)) {
                // Something was waiting and the day went by. That is the miss the streak ends
                // at; every other unreviewed day above is one the scheduler left empty.
                break;
            }
        }
        return streak;
    }

    private long reviewsOn(String userId, LocalDate day) {
        return reviewLogs.countBetween(userId, startOf(day), startOf(day.plusDays(1)));
    }

    private boolean anythingWasDueOn(String userId, LocalDate day) {
        return cards.existsCardDueOn(userId, startOf(day), startOf(day.plusDays(1)));
    }

    /**
     * A day boundary as an instant, in the server's zone — the same "today" the study queue
     * uses. Documented in {@code docs/api-contract.md} as a single-user simplification: a
     * client in another timezone would see days roll over at the server's midnight, not its
     * own.
     */
    private Instant startOf(LocalDate day) {
        return day.atStartOfDay(clock.getZone()).toInstant();
    }
}
