package dev.vsdeadshot.flashcards.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vsdeadshot.flashcards.domain.Card;
import dev.vsdeadshot.flashcards.domain.ReviewLog;
import dev.vsdeadshot.flashcards.domain.Topic;
import dev.vsdeadshot.flashcards.repository.ReviewLogRepository;
import dev.vsdeadshot.flashcards.scheduler.SchedulingState;
import dev.vsdeadshot.flashcards.support.EmbeddedPostgresTest;
import dev.vsdeadshot.flashcards.support.FixedClockConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

@Transactional
@Import(FixedClockConfiguration.class)
class StudyServiceTest extends EmbeddedPostgresTest {

    private static final String USER = "vedansh";
    private static final String OTHER_USER = "someone-else";
    private static final Instant NOW = FixedClockConfiguration.NOW;
    private static final LocalDate TODAY = FixedClockConfiguration.TODAY;
    private static final double EPSILON = 1e-9;

    @Autowired
    private StudyService service;

    @Autowired
    private CardService cardService;

    @Autowired
    private TopicService topicService;

    @Autowired
    private ReviewLogRepository reviewLogs;

    private Topic topic;

    @BeforeEach
    void seed() {
        topic = topicService.create(USER, "Operating Systems");
    }

    private Card newCard(String front) {
        return cardService.create(USER, topic.getId(), front, "an answer");
    }

    /** A card mid-ladder, so a review exercises the ease-scaled branch rather than 1 or 6. */
    private Card cardWith(SchedulingState state) {
        Card card = newCard("front " + state.dueDate());
        card.applySchedule(state, NOW);
        return card;
    }

    @Nested
    @DisplayName("the queue")
    class Queue {

        @Test
        @DisplayName("returns the cards due today, longest overdue first")
        void returnsDueCardsOldestFirst() {
            Card overdue = cardWith(new SchedulingState(2.5d, 1, 1, 0, TODAY.minusDays(3)));
            Card dueToday = newCard("due today");
            cardWith(new SchedulingState(2.5d, 6, 2, 0, TODAY.plusDays(4)));

            List<Card> queue = service.queue(USER, StudyService.DEFAULT_LIMIT);

            assertEquals(List.of(overdue.getId(), dueToday.getId()),
                    queue.stream().map(Card::getId).toList(),
                    "a card that is not due yet is not offered up");
        }

        @Test
        @DisplayName("clamps a limit larger than the maximum")
        void clampsAnOversizedLimit() {
            for (int i = 0; i < StudyService.MAX_LIMIT + 5; i++) {
                newCard("card " + i);
            }

            assertEquals(StudyService.MAX_LIMIT, service.queue(USER, 10_000).size(),
                    "a study session is not a bulk export");
        }

        @Test
        @DisplayName("rejects a limit of zero or less")
        void rejectsANonPositiveLimit() {
            assertThrows(IllegalArgumentException.class, () -> service.queue(USER, 0));
            assertThrows(IllegalArgumentException.class, () -> service.queue(USER, -1));
        }

        @Test
        @DisplayName("never offers another user's cards")
        void isScopedToOneUser() {
            newCard("mine");

            assertTrue(service.queue(OTHER_USER, StudyService.DEFAULT_LIMIT).isEmpty());
        }
    }

    @Nested
    @DisplayName("reviewing a card")
    class Review {

        @Test
        @DisplayName("reschedules it and takes it out of today's queue")
        void reschedulesAndLeavesTheQueue() {
            Card card = newCard("What is a deadlock?");

            Card reviewed = service.review(USER, card.getId(), 5);

            assertEquals(1, reviewed.getRepetitions());
            assertEquals(1, reviewed.getIntervalDays());
            assertEquals(TODAY.plusDays(1), reviewed.getDueDate());
            assertEquals(NOW, reviewed.getLastReviewedAt());
            assertTrue(service.queue(USER, StudyService.DEFAULT_LIMIT).isEmpty(),
                    "a card just reviewed is not due again today");
        }

        @Test
        @DisplayName("writes a log holding both sides of the transition")
        void writesTheReviewLog() {
            Card card = cardWith(new SchedulingState(2.5d, 10, 4, 0, TODAY));

            service.review(USER, card.getId(), 2);

            ReviewLog log = reviewLogs.findAll().stream().findFirst().orElseThrow();
            assertEquals(2, log.getConfidence());
            assertEquals(10, log.getIntervalBefore(), "the before values would be gone a moment later");
            assertEquals(1, log.getIntervalAfter());
            assertEquals(2.5d, log.getEaseFactorBefore(), EPSILON);
            assertEquals(2.5d, log.getEaseFactorAfter(), EPSILON);
            assertEquals(0, log.getRepetitionsAfter());
            assertEquals(NOW, log.getReviewedAt());
            assertEquals(USER, log.getUserId());
            assertTrue(log.isLapse());
        }

        @Test
        @DisplayName("resets repetitions on a lapse without touching the ease factor")
        void aLapseResetsRepetitionsOnly() {
            Card card = cardWith(new SchedulingState(2.5d, 10, 4, 0, TODAY));

            Card reviewed = service.review(USER, card.getId(), 2);

            assertEquals(0, reviewed.getRepetitions(), "recovery restarts at 1 day, then 6");
            assertEquals(1, reviewed.getLapses());
            assertEquals(1, reviewed.getIntervalDays());
            assertEquals(2.5d, reviewed.getEaseFactor(), EPSILON,
                    "one bad day must not permanently degrade the schedule");
        }

        @Test
        @DisplayName("allows studying ahead, measuring the next interval from today")
        void allowsReviewingEarly() {
            // Due in six days, reviewed now. round(6 * 2.5) = 15.
            Card card = cardWith(new SchedulingState(2.5d, 6, 2, 0, TODAY.plusDays(6)));

            Card reviewed = service.review(USER, card.getId(), 5);

            assertEquals(15, reviewed.getIntervalDays());
            assertEquals(TODAY.plusDays(15), reviewed.getDueDate(),
                    "the interval runs from the day the review happened, not the day it was due");
        }

        @Test
        @DisplayName("rejects a confidence outside 1 to 5")
        void rejectsAnOutOfRangeConfidence() {
            Card card = newCard("front");

            assertThrows(IllegalArgumentException.class, () -> service.review(USER, card.getId(), 0));
            assertThrows(IllegalArgumentException.class, () -> service.review(USER, card.getId(), 6));
        }

        @Test
        @DisplayName("refuses another user's card")
        void refusesAnotherUsersCard() {
            Card card = newCard("front");

            assertThrows(NotFoundException.class, () -> service.review(OTHER_USER, card.getId(), 4));
        }

        @Test
        @DisplayName("refuses an archived card rather than quietly rescheduling it")
        void refusesAnArchivedCard() {
            Card card = newCard("front");
            cardService.archive(USER, card.getId());

            assertThrows(NotFoundException.class, () -> service.review(USER, card.getId(), 4),
                    "a client with a stale queue must not revive a retired card");
        }

        @Test
        @DisplayName("leaves the card untouched when the confidence is rejected")
        void doesNotMutateOnInvalidInput() {
            Card card = newCard("front");

            assertThrows(IllegalArgumentException.class, () -> service.review(USER, card.getId(), 9));

            assertEquals(0, card.getRepetitions());
            assertEquals(TODAY, card.getDueDate());
            assertFalse(reviewLogs.findAll().stream().findAny().isPresent(),
                    "a rejected review must not leave a log behind");
        }
    }

    /**
     * The offline path. A client that studied without a connection sends the moment it
     * happened; the server would otherwise stamp the moment it heard about it, crediting the
     * wrong day to a streak that exists to reward not missing days.
     */
    @Nested
    @DisplayName("reviewing a card studied earlier")
    class BackdatedReview {

        private static final Instant YESTERDAY = NOW.minus(Duration.ofDays(1));

        @Test
        @DisplayName("logs it at the moment it happened, not the moment it arrived")
        void logsWhenItHappened() {
            Card card = newCard("front");

            Card reviewed = service.review(USER, card.getId(), 5, YESTERDAY);

            assertEquals(YESTERDAY, reviewed.getLastReviewedAt());
            assertEquals(YESTERDAY, reviewLogs.findAll().get(0).getReviewedAt(),
                    "the log is what the streak reads, so this is the field that matters");
        }

        @Test
        @DisplayName("measures the next interval from that day, so a late review can already be due")
        void measuresTheIntervalFromThatDay() {
            Card card = newCard("front");

            Card reviewed = service.review(USER, card.getId(), 5, YESTERDAY);

            assertEquals(1, reviewed.getIntervalDays());
            assertEquals(TODAY, reviewed.getDueDate(),
                    "one day after yesterday is today — the card is owed again now");
            assertFalse(service.queue(USER, StudyService.DEFAULT_LIMIT).isEmpty(),
                    "and is therefore back in the queue, which is the truthful answer");
        }

        @Test
        @DisplayName("treats an absent time as now, which is what an online client sends")
        void absentMeansNow() {
            Card card = newCard("front");

            assertEquals(NOW, service.review(USER, card.getId(), 5, null).getLastReviewedAt());
        }

        @Test
        @DisplayName("tolerates a device clock slightly ahead of the server's")
        void allowsSmallClockSkew() {
            Card card = newCard("front");
            Instant slightlyAhead = NOW.plus(Duration.ofMinutes(1));

            assertEquals(slightlyAhead,
                    service.review(USER, card.getId(), 5, slightlyAhead).getLastReviewedAt(),
                    "a phone a minute fast is not an error worth refusing a review over");
        }

        @Test
        @DisplayName("refuses a time meaningfully in the future")
        void refusesTheFuture() {
            Card card = newCard("front");
            Instant tomorrow = NOW.plus(Duration.ofDays(1));

            assertThrows(IllegalArgumentException.class,
                    () -> service.review(USER, card.getId(), 5, tomorrow),
                    "a review that has not happened yet cannot be recorded");
        }

        @Test
        @DisplayName("refuses a time older than the backdating window")
        void refusesTheDistantPast() {
            Card card = newCard("front");
            Instant tooOld = NOW.minus(StudyService.MAX_BACKDATE).minus(Duration.ofDays(1));

            assertThrows(IllegalArgumentException.class,
                    () -> service.review(USER, card.getId(), 5, tooOld),
                    "a device whose clock is wrong by months should be refused, not believed");
        }

        /**
         * Out-of-order arrival. One client replaying its own queue in order cannot cause this;
         * something that does is confused, and rewinding a schedule computed from newer
         * information would corrupt it silently.
         */
        @Test
        @DisplayName("refuses a review older than the card's last one")
        void refusesToRewindACard() {
            Card card = newCard("front");
            service.review(USER, card.getId(), 5);

            assertThrows(IllegalArgumentException.class,
                    () -> service.review(USER, card.getId(), 4, YESTERDAY));
        }

        @Test
        @DisplayName("leaves the card untouched when the time is rejected")
        void doesNotMutateOnARejectedTime() {
            Card card = newCard("front");

            assertThrows(IllegalArgumentException.class,
                    () -> service.review(USER, card.getId(), 5, NOW.plus(Duration.ofDays(1))));

            assertEquals(0, card.getRepetitions());
            assertEquals(TODAY, card.getDueDate());
            assertTrue(reviewLogs.findAll().isEmpty(), "and no log behind it");
        }
    }
}
