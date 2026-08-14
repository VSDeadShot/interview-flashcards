package dev.vsdeadshot.flashcards.data.remote.dto;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A card as the server returns it — from a listing, a create, an update, the study queue, and
 * the result of a review, which are deliberately one shape on the server so a review's answer
 * can replace what the queue put in the cache.
 *
 * <p>The scheduling fields are primitives on purpose. A missing one would leave a boxed field
 * null and a primitive at zero, but Moshi refuses a JSON null for a primitive outright, so a
 * server that stopped sending {@code easeFactor} fails here instead of quietly rescheduling
 * every card from 0.0.
 */
public class CardDto {

    public long id;
    public long topicId;
    public String front;
    public String back;
    public double easeFactor;
    public int intervalDays;
    public int repetitions;
    public int lapses;
    public LocalDate dueDate;

    /** Null until the card's first review. Boxed for exactly that reason. */
    public Instant lastReviewedAt;

    public boolean archived;
}
