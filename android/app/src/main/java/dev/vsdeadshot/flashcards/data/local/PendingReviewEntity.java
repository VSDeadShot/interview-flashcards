package dev.vsdeadshot.flashcards.data.local;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;
import java.time.Instant;
import java.util.UUID;

/**
 * A review performed on this device that the server has not accepted yet — the outbox.
 *
 * <p>This table is the only record of what work is outstanding. A card is deliberately not
 * flagged as "dirty" alongside it: two places claiming to know whether a review is pending can
 * disagree, and then a card is either stuck out of the queue or synced twice.
 *
 * <p>Rows are replayed in {@code id} order and deleted once the server has answered. That
 * ordering is required rather than tidy: the server refuses a review older than the card's
 * last one, so replaying yesterday's review after today's would be rejected outright.
 */
@Entity(
        tableName = "pending_review",
        // The key the server deduplicates on. Unique here too, so a bug that enqueued the same
        // review twice is caught on this side rather than costing a round trip to find out.
        indices = {@Index(value = {"clientReviewId"}, unique = true)})
public class PendingReviewEntity {

    /** Local only, and the replay order. Nothing on the server has this id. */
    @PrimaryKey(autoGenerate = true)
    public long id;

    public long cardId;

    public int confidence;

    /**
     * When the user actually answered the card. Sent to the server, which uses it for both the
     * review log and the day the next interval runs from — so a review synced days later still
     * counts for the day it happened, and the streak is not broken by having had no signal.
     */
    public Instant reviewedAt;

    /**
     * Generated when the review is enqueued, not when it is sent. Every retry of this row
     * carries the same value, which is what makes a lost response safe: the server recognises
     * the repeat instead of applying SM-2 a second time.
     */
    @NonNull
    public UUID clientReviewId = new UUID(0L, 0L);

    /** How many times sending this has failed. Kept for backoff and for showing a stuck queue. */
    public int attempts;

    /** The last failure's {@code detail}, or null. Null once it has never failed. */
    public String lastError;
}
