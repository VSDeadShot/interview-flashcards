package dev.vsdeadshot.flashcards.data.local;

import androidx.room.Entity;
import androidx.room.PrimaryKey;
import java.time.Instant;

/**
 * The one figure this client cannot work out for itself, and when it was told.
 *
 * <p>Only the streak is here. Every other number on a stats screen is counted from the cache,
 * which is the more current answer — it includes cards written and retired since the last sync —
 * so keeping the server's copy of those as well would only be two numbers that can disagree.
 *
 * <p>The streak is different in kind. Its rule skips days on which nothing was due, which needs
 * the whole review history and every card's due date as it stood on each of those days. A local
 * approximation would be wrong in exactly the case the rule exists for: it would tell someone
 * who studied everything they had that they broke their streak.
 *
 * <p>One row, by construction. There is one user and one server.
 */
@Entity(tableName = "stats_snapshot")
public class StatsSnapshotEntity {

    public static final int ROW_ID = 1;

    @PrimaryKey
    public int id = ROW_ID;

    public int currentStreakDays;

    /**
     * When this client asked, by its own clock — the server sends no timestamp of its own. It is
     * shown next to the figure rather than kept for bookkeeping: a streak with no "as of" claims
     * to be current, and this one is only ever as current as the last sync.
     */
    public Instant fetchedAt;
}
