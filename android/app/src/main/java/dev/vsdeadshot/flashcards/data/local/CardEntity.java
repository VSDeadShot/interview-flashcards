package dev.vsdeadshot.flashcards.data.local;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;
import dev.vsdeadshot.flashcards.scheduler.SchedulingState;
import dev.vsdeadshot.flashcards.scheduler.Sm2Scheduler;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * A card, holding exactly what {@code CardResponse} carries and nothing invented locally.
 *
 * <p>Archived cards are stored rather than dropped. The sync pulls with
 * {@code includeArchived=true} on purpose: a card that simply vanished from a listing would be
 * indistinguishable from one that was deleted, moved, or missed, and this flag is what tells
 * the difference.
 *
 * <p><strong>Not every row here is a cache.</strong> A card written on this device has no
 * {@code serverId} until a sync has created it, and until then this row is the only copy of
 * something the user typed. The pure-cache rule the rest of this database follows narrows to
 * rows that have one — which is why the pull's deletes are scoped to those, and why a
 * destructive migration may not be applied to this table.
 *
 * <p>The scheduling columns are written twice over: once by a pull, and once by the local
 * scheduler when a review is recorded offline. The second is a <em>prediction</em> — the
 * server's answer replaces it when the queued review is accepted. Both run the same SM-2 on
 * the same inputs, so the prediction is normally the value that comes back.
 */
@Entity(
        tableName = "card",
        // Covers the only query the study screen makes. The partial index the server uses is
        // not available here, so archived is part of the key rather than a predicate on it.
        indices = {
            @Index(value = {"archived", "dueDate", "id"}),
            // Both nullable, and SQLite lets a unique index hold any number of nulls — which is
            // what makes "many cards not yet on the server" and "many cards this device did not
            // write" both legal.
            @Index(value = {"serverId"}, unique = true),
            @Index(value = {"clientCardId"}, unique = true)
        })
public class CardEntity {

    /**
     * Local, and fixed for the life of the row. It is deliberately not the server's id: a card
     * written offline has no server id yet, and if this column later had to change to become
     * one, every {@code pending_review.cardId} pointing here would have to be rewritten — on
     * the path that runs immediately after a network response, in the one table that must
     * never be corrupted. Cards that arrive from a pull are stored under the server's id
     * because it is already a free local id, not because the two mean the same thing.
     */
    @PrimaryKey
    public long id;

    /**
     * The server's id, or null for a card written here that no sync has created yet. This is
     * what a pull matches on, so it is what decides whether a row is a cache of something or
     * the only copy of it.
     */
    public Long serverId;

    /**
     * The key this device minted when it wrote the card, or null for a card that came from the
     * server. Sent as {@code clientCardId} on the create and echoed back on every card the
     * server returns, which is what lets a create whose response was lost be recognised in a
     * later listing instead of appearing as a second card.
     */
    public UUID clientCardId;

    public long topicId;

    @NonNull
    public String front = "";

    @NonNull
    public String back = "";

    public double easeFactor;

    public int intervalDays;

    public int repetitions;

    public int lapses;

    public LocalDate dueDate;

    /** Null until the first review, exactly as on the wire. */
    public Instant lastReviewedAt;

    public boolean archived;

    /**
     * This card's schedule as the scheduler sees it.
     *
     * <p>The same bridge the backend's {@code Card} exposes, and for the same reason: the
     * dependency runs local → scheduler and never back, so nothing in {@code scheduler} has to
     * know Room exists. These two methods are the only crossing.
     */
    public SchedulingState schedulingState() {
        return new SchedulingState(easeFactor, intervalDays, repetitions, lapses, dueDate);
    }

    /**
     * Writes a scheduler result back onto this card.
     *
     * <p>Offline this is a <em>prediction</em>: the server runs the same arithmetic on the same
     * inputs when the queued review reaches it, and its answer replaces these values. Both
     * normally agree, which is the point of the scheduler being duplicated rather than guessed at.
     *
     * @param next the state returned by {@link Sm2Scheduler#schedule}
     * @param reviewedAt when the review that produced {@code next} happened
     */
    public void applySchedule(SchedulingState next, Instant reviewedAt) {
        Objects.requireNonNull(next, "next must not be null");
        this.easeFactor = next.easeFactor();
        this.intervalDays = next.intervalDays();
        this.repetitions = next.repetitions();
        this.lapses = next.lapses();
        this.dueDate = next.dueDate();
        this.lastReviewedAt = Objects.requireNonNull(reviewedAt, "reviewedAt must not be null");
    }
}
