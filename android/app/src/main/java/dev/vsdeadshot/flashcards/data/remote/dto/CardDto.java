package dev.vsdeadshot.flashcards.data.remote.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

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

    /**
     * The key whoever created this card sent with it, or null — for a card the server made
     * itself, or one created before this field existed. Boxed, and nullable, because most cards
     * do not have one.
     *
     * <p>This is what lets a create whose response was lost be recognised. The card is on the
     * server and still queued here; the id it comes back under means nothing to this client, but
     * the key does, and it names the local row exactly.
     */
    public UUID clientCardId;
}
