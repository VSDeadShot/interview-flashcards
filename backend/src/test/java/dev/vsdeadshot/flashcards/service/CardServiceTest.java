package dev.vsdeadshot.flashcards.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vsdeadshot.flashcards.domain.Card;
import dev.vsdeadshot.flashcards.domain.Topic;
import dev.vsdeadshot.flashcards.repository.CardRepository;
import dev.vsdeadshot.flashcards.scheduler.SchedulingState;
import dev.vsdeadshot.flashcards.scheduler.Sm2Scheduler;
import dev.vsdeadshot.flashcards.support.EmbeddedPostgresTest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Limit;
import org.springframework.transaction.annotation.Transactional;

@Transactional
@Import(CardServiceTest.FixedClockConfiguration.class)
class CardServiceTest extends EmbeddedPostgresTest {

    private static final String USER = "vedansh";
    private static final String OTHER_USER = "someone-else";
    private static final Instant NOW = Instant.parse("2026-03-17T09:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 17);
    private static final double EPSILON = 1e-9;

    /**
     * A fixed clock, so "due today" is a date this test can assert on rather than whatever day
     * the suite happens to run. This is the reason {@link CardService} takes a {@link Clock}
     * instead of calling {@code LocalDate.now()}.
     *
     * <p>Pulled in with an explicit {@code @Import} rather than left as a nested
     * {@code @TestConfiguration}: Boot only scans for those on the class it is bootstrapping,
     * and each {@code @Nested} class is bootstrapped separately, so the inner tests would
     * quietly build a context holding the real system clock instead.
     *
     * <p>{@link #NOW} is deliberately not today. A fixed date that happens to match the day
     * the suite runs makes these assertions pass whether the clock is honoured or not.
     */
    @Configuration
    static class FixedClockConfiguration {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    private CardService service;

    @Autowired
    private TopicService topicService;

    @Autowired
    private CardRepository cards;

    private Topic mine;
    private Topic alsoMine;
    private Topic theirs;

    @BeforeEach
    void seed() {
        mine = topicService.create(USER, "Operating Systems");
        alsoMine = topicService.create(USER, "Databases");
        theirs = topicService.create(OTHER_USER, "Networks");
    }

    @Nested
    @DisplayName("creating a card")
    class Create {

        @Test
        @DisplayName("starts it due today with the schedule of an unreviewed card")
        void startsUnreviewedAndDueToday() {
            Card card = service.create(USER, mine.getId(), "What is a deadlock?", "Coffman conditions.");

            assertEquals(TODAY, card.getDueDate(), "a card added now is studiable now");
            assertEquals(Sm2Scheduler.INITIAL_EASE_FACTOR, card.getEaseFactor(), EPSILON);
            assertEquals(0, card.getRepetitions());
            assertFalse(card.isArchived());
        }

        @Test
        @DisplayName("trims the question and answer")
        void trimsBothSides() {
            Card card = service.create(USER, mine.getId(), "  What is 2PL?  ", "  Two-phase locking. ");

            assertEquals("What is 2PL?", card.getFront());
            assertEquals("Two-phase locking.", card.getBack());
        }

        @Test
        @DisplayName("rejects a blank question or answer")
        void rejectsBlankSides() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.create(USER, mine.getId(), "   ", "an answer"));
            assertThrows(IllegalArgumentException.class,
                    () -> service.create(USER, mine.getId(), "a question", null));
        }

        @Test
        @DisplayName("refuses to file a card under another user's topic")
        void refusesAnotherUsersTopic() {
            assertThrows(NotFoundException.class,
                    () -> service.create(USER, theirs.getId(), "front", "back"),
                    "a topic id owned by someone else must not be usable");
        }

        @Test
        @DisplayName("refuses an unknown topic")
        void refusesAnUnknownTopic() {
            assertThrows(NotFoundException.class, () -> service.create(USER, 999_999L, "front", "back"));
        }
    }

    @Nested
    @DisplayName("updating a card")
    class Update {

        @Test
        @DisplayName("replaces the text without disturbing the schedule")
        void leavesTheScheduleAlone() {
            Card card = service.create(USER, mine.getId(), "typo", "back");
            card.applySchedule(new SchedulingState(2.6d, 6, 2, 1, TODAY.plusDays(6)), NOW);

            Card updated = service.update(USER, card.getId(), "fixed", "back", mine.getId());

            assertEquals("fixed", updated.getFront());
            assertEquals(6, updated.getIntervalDays(), "correcting a typo must not reset progress");
            assertEquals(2, updated.getRepetitions());
            assertEquals(1, updated.getLapses());
            assertEquals(TODAY.plusDays(6), updated.getDueDate());
        }

        @Test
        @DisplayName("moves a card between the user's own topics")
        void movesBetweenOwnTopics() {
            Card card = service.create(USER, mine.getId(), "front", "back");

            Card updated = service.update(USER, card.getId(), "front", "back", alsoMine.getId());

            assertEquals(alsoMine.getId(), updated.getTopic().getId());
        }

        @Test
        @DisplayName("refuses to move a card into another user's topic")
        void refusesToMoveIntoAnotherUsersTopic() {
            Card card = service.create(USER, mine.getId(), "front", "back");

            assertThrows(NotFoundException.class,
                    () -> service.update(USER, card.getId(), "front", "back", theirs.getId()),
                    "otherwise a card could be filed under a topic the caller does not own");
        }

        @Test
        @DisplayName("refuses to touch another user's card")
        void refusesAnotherUsersCard() {
            Card card = service.create(USER, mine.getId(), "front", "back");

            assertThrows(NotFoundException.class,
                    () -> service.update(OTHER_USER, card.getId(), "hacked", "hacked", theirs.getId()));
        }
    }

    @Nested
    @DisplayName("archiving a card")
    class Archive {

        @Test
        @DisplayName("takes it out of the study queue but keeps the row")
        void removesFromTheQueueWithoutDeleting() {
            Card card = service.create(USER, mine.getId(), "front", "back");
            assertTrue(inQueue(card), "a new card is due today, so it starts in the queue");

            service.archive(USER, card.getId());

            assertFalse(inQueue(card), "archived cards are never studied");
            assertTrue(service.list(USER, null, true).contains(card),
                    "the row survives so its review history still resolves");
        }

        @Test
        @DisplayName("is a no-op the second time, so DELETE stays idempotent")
        void isIdempotent() {
            Card card = service.create(USER, mine.getId(), "front", "back");

            service.archive(USER, card.getId());
            service.archive(USER, card.getId());

            assertTrue(service.get(USER, card.getId()).isArchived());
        }

        @Test
        @DisplayName("refuses another user's card")
        void refusesAnotherUsersCard() {
            Card card = service.create(USER, mine.getId(), "front", "back");

            assertThrows(NotFoundException.class, () -> service.archive(OTHER_USER, card.getId()));
        }

        private boolean inQueue(Card card) {
            List<Card> queue = cards.findStudyQueue(USER, TODAY, Limit.unlimited());
            return queue.contains(card);
        }
    }
}
