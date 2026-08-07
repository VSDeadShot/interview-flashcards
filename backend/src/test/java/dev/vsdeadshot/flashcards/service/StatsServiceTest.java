package dev.vsdeadshot.flashcards.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vsdeadshot.flashcards.domain.Card;
import dev.vsdeadshot.flashcards.domain.ReviewLog;
import dev.vsdeadshot.flashcards.domain.Topic;
import dev.vsdeadshot.flashcards.repository.ReviewLogRepository;
import dev.vsdeadshot.flashcards.scheduler.SchedulingState;
import dev.vsdeadshot.flashcards.support.EmbeddedPostgresTest;
import dev.vsdeadshot.flashcards.support.FixedClockConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/**
 * The streak is the only part of {@code /stats} with a judgement in it, so most of this class
 * is about days rather than counts.
 *
 * <p>Building a history means writing rows the API cannot produce on demand — a card created
 * last week, a review that happened three days ago. Everything the service writes is stamped
 * with the fixed clock, which puts it all on one day, so {@link #backdate(Card, LocalDate)}
 * moves a card into the past by setting {@code created_at} and {@code due_date} directly.
 * Both are deliberately unreachable through the domain, which is why it is done in SQL.
 */
@Transactional
@Import(FixedClockConfiguration.class)
class StatsServiceTest extends EmbeddedPostgresTest {

    private static final LocalDate TODAY = FixedClockConfiguration.TODAY;
    private static final String OTHER_USER = "someone-else";

    @Autowired
    private StatsService stats;

    @Autowired
    private TopicService topics;

    @Autowired
    private CardService cards;

    @Autowired
    private StudyService study;

    @Autowired
    private ReviewLogRepository reviewLogs;

    @PersistenceContext
    private EntityManager em;

    private Topic operatingSystems;

    @BeforeEach
    void seedTopic() {
        operatingSystems = topics.create(TEST_USER_ID, "Operating Systems");
    }

    private Card newCard(String front) {
        return cards.create(TEST_USER_ID, operatingSystems.getId(), front, "back");
    }

    /** The test clock runs in UTC, so a day's instants are its UTC instants. */
    private static Instant noonOn(LocalDate day) {
        return day.atStartOfDay(ZoneOffset.UTC).plusHours(12).toInstant();
    }

    /**
     * Moves a card into the past as though it had been written that day and never touched:
     * created then, and due then, because a new card is due immediately.
     *
     * <p>Native, because neither column is settable through the domain — {@code created_at} is
     * {@code updatable = false} and {@code due_date} only moves through the scheduler. The
     * refresh afterwards resyncs the managed instance, so a later flush cannot write the stale
     * in-memory due date back over this one.
     */
    private void backdate(Card card, LocalDate day) {
        em.flush();
        em.createNativeQuery("update card set created_at = :at, due_date = :due where id = :id")
                .setParameter("at", noonOn(day))
                .setParameter("due", day)
                .setParameter("id", card.getId())
                .executeUpdate();
        em.refresh(card);
    }

    /**
     * A review that happened on a past day, written the way the service would have written it:
     * a log entry plus the card's new schedule, so the card and its history agree.
     */
    private void reviewedOn(Card card, LocalDate day, int nextIntervalDays) {
        SchedulingState before = card.schedulingState();
        SchedulingState after = new SchedulingState(
                before.easeFactor(),
                nextIntervalDays,
                before.repetitions() + 1,
                before.lapses(),
                day.plusDays(nextIntervalDays));
        Instant at = noonOn(day);
        card.applySchedule(after, at);
        reviewLogs.save(ReviewLog.of(card, 4, before, after, at));
        em.flush();
    }

    private int streak() {
        return stats.forUser(TEST_USER_ID).currentStreakDays();
    }

    @Nested
    @DisplayName("the counts")
    class Counts {

        @Test
        @DisplayName("leave archived cards out of the totals")
        void excludesArchivedCards() {
            newCard("kept");
            Card retired = newCard("retired");
            cards.archive(TEST_USER_ID, retired.getId());

            Stats result = stats.forUser(TEST_USER_ID);

            assertEquals(1, result.totalCards(), "an archived card is out of circulation");
            assertEquals(1, result.dueToday(), "and so is out of what is due");
        }

        @Test
        @DisplayName("count a card due today and one overdue, but not one scheduled ahead")
        void countsWhatIsDue() {
            newCard("due today");
            Card overdue = newCard("overdue");
            backdate(overdue, TODAY.minusDays(3));
            Card ahead = newCard("scheduled ahead");
            reviewedOn(ahead, TODAY, 6);

            assertEquals(2, stats.forUser(TEST_USER_ID).dueToday(),
                    "overdue cards are still due; a card the scheduler pushed forward is not");
        }

        @Test
        @DisplayName("count only today's reviews as reviewed today")
        void countsTodaysReviews() {
            Card card = newCard("front");
            backdate(card, TODAY.minusDays(5));
            reviewedOn(card, TODAY.minusDays(1), 1);
            reviewedOn(card, TODAY, 6);

            assertEquals(1, stats.forUser(TEST_USER_ID).reviewedToday(),
                    "yesterday's review belongs to yesterday");
        }

        @Test
        @DisplayName("break down by topic, including a topic holding nothing")
        void breaksDownByTopic() {
            Topic databases = topics.create(TEST_USER_ID, "Databases");
            topics.create(TEST_USER_ID, "Zero Topic");
            newCard("os one");
            newCard("os two");
            Card ahead = cards.create(TEST_USER_ID, databases.getId(), "db", "back");
            reviewedOn(ahead, TODAY, 6);

            List<TopicStats> byTopic = stats.forUser(TEST_USER_ID).byTopic();

            assertEquals(3, byTopic.size(), "every topic appears, in name order");
            assertEquals(new TopicStats(databases.getId(), "Databases", 1, 0), byTopic.get(0));
            assertEquals(
                    new TopicStats(operatingSystems.getId(), "Operating Systems", 2, 2),
                    byTopic.get(1));
            assertEquals("Zero Topic", byTopic.get(2).name(),
                    "a topic with no cards is still a topic, and reports zeros");
            assertEquals(0, byTopic.get(2).total());
        }

        @Test
        @DisplayName("never include another user's cards or topics")
        void areScopedToOneUser() {
            Topic theirs = topics.create(OTHER_USER, "Theirs");
            cards.create(OTHER_USER, theirs.getId(), "theirs", "theirs");

            Stats result = stats.forUser(TEST_USER_ID);

            assertEquals(0, result.totalCards());
            assertTrue(result.byTopic().stream().noneMatch(t -> t.name().equals("Theirs")),
                    "the breakdown is the caller's topics only");
        }
    }

    @Nested
    @DisplayName("the streak")
    class Streak {

        @Test
        @DisplayName("is zero for a user with no cards at all")
        void isZeroWithoutCards() {
            assertEquals(0, streak());
        }

        @Test
        @DisplayName("counts today when today has been studied")
        void countsToday() {
            Card card = newCard("front");
            backdate(card, TODAY);
            reviewedOn(card, TODAY, 1);

            assertEquals(1, streak());
        }

        @Test
        @DisplayName("counts consecutive studied days")
        void countsConsecutiveDays() {
            Card card = newCard("front");
            backdate(card, TODAY.minusDays(2));
            reviewedOn(card, TODAY.minusDays(2), 1);
            reviewedOn(card, TODAY.minusDays(1), 1);
            reviewedOn(card, TODAY, 1);

            assertEquals(3, streak());
        }

        /**
         * The day is not over. A streak that reads zero at breakfast and repairs itself after
         * the first card of the evening would be reporting the clock, not the habit.
         */
        @Test
        @DisplayName("survives a today that has not been studied yet")
        void todayDoesNotBreakIt() {
            Card card = newCard("front");
            backdate(card, TODAY.minusDays(3));
            reviewedOn(card, TODAY.minusDays(2), 1);
            reviewedOn(card, TODAY.minusDays(1), 5);

            assertEquals(2, streak(), "yesterday and the day before still count");
        }

        /**
         * The forgiving rule, and the reason for the second query per day: the card was
         * scheduled past the gap day, so there was nothing to study and nothing to forgive.
         */
        @Test
        @DisplayName("runs across a day on which nothing was due")
        void skipsADayWithNothingDue() {
            Card card = newCard("front");
            backdate(card, TODAY.minusDays(4));
            reviewedOn(card, TODAY.minusDays(4), 1);
            // Due again three days later — nothing was due on the two days in between.
            reviewedOn(card, TODAY.minusDays(3), 3);
            reviewedOn(card, TODAY, 6);

            assertEquals(3, streak(),
                    "today plus the two studied days, with the empty days skipped rather than "
                            + "counted or treated as misses");
        }

        @Test
        @DisplayName("ends at a day that had something due and went unstudied")
        void breaksOnAMissedDay() {
            Card card = newCard("front");
            backdate(card, TODAY.minusDays(4));
            // Due every day since it was written, and untouched until today.
            reviewedOn(card, TODAY, 1);

            assertEquals(1, streak(), "yesterday had the card waiting and nothing happened");
        }

        /**
         * The interaction the "day with nothing due" check has with cards written mid-day. A
         * card added during a day cannot have been missed earlier in it, so it does not turn
         * that day into a failure — otherwise writing cards last thing at night would break the
         * streak of the person doing the writing.
         */
        @Test
        @DisplayName("is not broken by a card created during an unstudied day")
        void aCardWrittenDuringTheDayDoesNotBreakIt() {
            Card studied = newCard("studied");
            backdate(studied, TODAY.minusDays(4));
            reviewedOn(studied, TODAY.minusDays(2), 3);
            reviewedOn(studied, TODAY, 1);

            Card writtenYesterday = newCard("written during the gap");
            backdate(writtenYesterday, TODAY.minusDays(1));

            assertEquals(2, streak(),
                    "today and the day before yesterday, with yesterday skipped: it holds a card "
                            + "that did not exist when the day began, so the day was still empty "
                            + "at every point it could have been studied. Counting that card "
                            + "would end the streak here at 1");
        }

        @Test
        @DisplayName("is not broken by an archived card that was due")
        void anArchivedCardDoesNotBreakIt() {
            Card studied = newCard("studied");
            backdate(studied, TODAY.minusDays(4));
            reviewedOn(studied, TODAY.minusDays(2), 3);
            reviewedOn(studied, TODAY, 1);

            Card retired = newCard("retired");
            backdate(retired, TODAY.minusDays(4));
            cards.archive(TEST_USER_ID, retired.getId());

            assertEquals(2, streak(),
                    "a card the user has retired cannot break a streak in hindsight — there is "
                            + "no record of when it was archived, so it never counts. Counting "
                            + "it would end the streak at yesterday, at 1");
        }

        /**
         * Termination, not arithmetic. Every day before the first review had nothing due once
         * the cards are this new, so a forgiving walk with no floor would skip backwards
         * through them forever rather than returning at all.
         */
        @Test
        @DisplayName("stops at the day of the first review")
        void stopsAtTheFirstReview() {
            Card card = newCard("front");
            backdate(card, TODAY);
            reviewedOn(card, TODAY, 1);

            assertEquals(1, streak());
        }

        /**
         * A card can exist long before anything is studied. The floor is the first review and
         * not the first card, so those earlier days are never walked — they could only ever be
         * skipped, since a day with no review cannot add to a streak.
         */
        @Test
        @DisplayName("is zero when there are cards but nothing has ever been reviewed")
        void isZeroWithoutReviews() {
            Card card = newCard("front");
            backdate(card, TODAY.minusDays(10));

            assertEquals(0, streak());
        }

        /**
         * The whole reason a review carries its own timestamp. Both reviews below reach the
         * server now; one of them happened yesterday. Stamped on arrival they would collapse
         * onto today, yesterday would read as a day with a card due and nothing done, and the
         * streak would end there at 1 — punishing the user for having had no signal.
         */
        @Test
        @DisplayName("counts a day whose review only reached the server later")
        void countsALateArrivingReview() {
            Card card = newCard("front");
            backdate(card, TODAY.minusDays(3));
            reviewedOn(card, TODAY.minusDays(2), 1);

            study.review(TEST_USER_ID, card.getId(), 5, noonOn(TODAY.minusDays(1)));

            assertEquals(2, streak(),
                    "yesterday and the day before it, both studied, one of them reported late");
        }

        @Test
        @DisplayName("does not count another user's reviews")
        void isScopedToOneUser() {
            Topic theirs = topics.create(OTHER_USER, "Theirs");
            Card theirCard = cards.create(OTHER_USER, theirs.getId(), "theirs", "theirs");
            reviewedOn(theirCard, TODAY, 1);

            assertEquals(0, streak(), "the caller has no cards, so no streak");
        }
    }
}
