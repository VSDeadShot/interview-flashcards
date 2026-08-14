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

/**
 * A card, holding exactly what {@code CardResponse} carries and nothing invented locally.
 *
 * <p>Archived cards are stored rather than dropped. The sync pulls with
 * {@code includeArchived=true} on purpose: a card that simply vanished from a listing would be
 * indistinguishable from one that was deleted, moved, or missed, and this flag is what tells
 * the difference.
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
        indices = {@Index(value = {"archived", "dueDate", "id"})})
public class CardEntity {

    /** The server's id. Slice 2 adds locally-created cards, which will need a local id too. */
    @PrimaryKey
    public long id;

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
