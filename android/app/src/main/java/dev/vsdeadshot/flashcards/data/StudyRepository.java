package dev.vsdeadshot.flashcards.data;

import dev.vsdeadshot.flashcards.data.local.CardEntity;
import dev.vsdeadshot.flashcards.data.local.FlashcardsDatabase;
import dev.vsdeadshot.flashcards.data.local.TopicEntity;
import dev.vsdeadshot.flashcards.scheduler.Sm2Scheduler;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * The card to study next, answered from the cache.
 *
 * <p>One card, not a session's worth. Every branch of {@link Sm2Scheduler#schedule} returns a due
 * date of {@code today.plusDays(n)} with {@code n} at least 1 — a lapse included — so an answered
 * card always leaves today's queue and re-reading the head always advances. A list loaded once and
 * walked by an index would buy nothing for that and would go stale the moment a sync archived
 * something in it.
 *
 * <p>Composed in one transaction for the same reason {@link StatsRepository} is: the card and the
 * counts shown beside it should describe one moment, not three.
 */
public final class StudyRepository {

    private final FlashcardsDatabase db;
    private final Clock clock;

    public StudyRepository(FlashcardsDatabase db) {
        this(db, Clock.systemDefaultZone());
    }

    public StudyRepository(FlashcardsDatabase db, Clock clock) {
        this.db = Objects.requireNonNull(db, "db must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public StudyView next() {
        LocalDate today = LocalDate.now(clock);

        return db.runInTransaction(() -> {
            // Limit 1: this reads the head, and the head is the whole answer. See the note above
            // on why re-reading it cannot serve the same card twice.
            List<CardEntity> head = db.cards().queue(today, 1);
            CardEntity card = head.isEmpty() ? null : head.get(0);

            String topicName = null;
            if (card != null) {
                TopicEntity topic = db.topics().findById(card.topicId);
                // Null when a card arrived in a pull ahead of its topic, or its topic was deleted
                // on the server. Worth showing the card anyway — the question is the point, and
                // the heading is decoration on it.
                topicName = topic == null ? null : topic.name;
            }

            return new StudyView(
                    card,
                    topicName,
                    db.cards().countDue(today),
                    // Lives on StatsDao because stats asked for it first. Spelling the same
                    // question a second time in CardDao would give two counts that could drift.
                    db.stats().totalCards());
        });
    }

    /**
     * What the study screen shows.
     *
     * @param card the card to answer, or <strong>null when nothing is due</strong>
     * @param topicName the card's topic, or null if the cache does not hold it
     * @param dueCount how many cards are due today in total, this one included
     * @param totalCards cards in circulation, archived ones excluded
     */
    public record StudyView(CardEntity card, String topicName, int dueCount, int totalCards) {

        /** Whether there is anything to answer. */
        public boolean hasCard() {
            return card != null;
        }

        /**
         * Whether the cache is empty rather than merely caught up.
         *
         * <p>The two are different things to say and only one of them is true of a fresh install.
         * "You are done for today", shown to somebody who has never had a card, is a congratulation
         * on nothing and hides the fact that a sync has not run.
         */
        public boolean isEmptyCache() {
            return totalCards == 0;
        }
    }
}
