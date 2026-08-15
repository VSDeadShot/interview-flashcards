package dev.vsdeadshot.flashcards.data;

import dev.vsdeadshot.flashcards.data.local.FlashcardsDatabase;
import dev.vsdeadshot.flashcards.data.local.StatsSnapshotEntity;
import dev.vsdeadshot.flashcards.data.local.TopicStatsRow;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * What a stats screen shows, answered without a network.
 *
 * <p>Everything except the streak is counted from the cache at the moment it is asked for. That
 * is not only for working offline: the number that matters most is how many cards are still due,
 * and it has to fall as the user answers them. A figure taken from the server's last answer would
 * sit still while somebody worked through their queue.
 *
 * <p>The cache is also the <em>more</em> current source. It holds cards written and retired on
 * this device that no sync has told the server about yet, so its counts are right before the
 * server's are.
 */
public final class StatsRepository {

    private final FlashcardsDatabase db;
    private final Clock clock;

    public StatsRepository(FlashcardsDatabase db) {
        this(db, Clock.systemDefaultZone());
    }

    public StatsRepository(FlashcardsDatabase db, Clock clock) {
        this.db = Objects.requireNonNull(db, "db must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public StatsView snapshot() {
        LocalDate today = LocalDate.now(clock);

        return db.runInTransaction(() -> {
            StatsSnapshotEntity streak = db.stats().snapshot();
            Integer reviewed = db.stats().reviewsOn(today);

            return new StatsView(
                    db.stats().totalCards(),
                    db.cards().countDue(today),
                    reviewed == null ? 0 : reviewed,
                    streak == null ? null : streak.currentStreakDays,
                    streak == null ? null : streak.fetchedAt,
                    db.stats().byTopic(today));
        });
    }

    /**
     * The figures behind a stats screen.
     *
     * @param totalCards cards in circulation — archived ones are out of the app and not counted
     * @param dueToday how many of those are due now, falling as the user answers them
     * @param reviewedToday reviews answered on this device today, including ones not yet sent
     * @param streakDays the server's streak, or <strong>null when it has never been fetched</strong>
     * @param streakAsOf when this client last asked, or null for the same reason
     * @param byTopic every topic, including ones holding no cards
     */
    public record StatsView(
            int totalCards,
            int dueToday,
            int reviewedToday,
            Integer streakDays,
            Instant streakAsOf,
            List<TopicStatsRow> byTopic) {

        /**
         * Whether there is a streak to show at all.
         *
         * <p>Null and zero are different answers and a screen must not confuse them: "we have
         * never been told" shown as a confident zero, to somebody thirty days into a run, is the
         * worst thing this feature could do.
         */
        public boolean hasStreak() {
            return streakDays != null;
        }
    }
}
