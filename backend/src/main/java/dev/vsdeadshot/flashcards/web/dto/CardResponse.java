package dev.vsdeadshot.flashcards.web.dto;

import dev.vsdeadshot.flashcards.domain.Card;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A card as the API returns it, everywhere it is returned — listing, creating, updating, the
 * study queue, and the result of a review. There is deliberately one of these rather than a
 * lean "queue card" and a fat "detail card": the client caches cards by id, and two shapes for
 * one id would mean a review's response could not replace what the queue put there.
 *
 * <p>Which is why the scheduling fields are here even though nothing in the card screens uses
 * them. After {@code POST /study/{id}/review} they are the entire point of the response.
 *
 * <p>The topic is flattened to {@code topicId} rather than nested. The client already has the
 * topic list, so nesting the row would send the same name back on every card, and reading it
 * here would mean touching a lazy association after the transaction has closed.
 *
 * <p>{@code clientCardId} is the caller's own key echoed back, and is null for most cards — any
 * the server made directly, and any another client created. It is here rather than only on the
 * create response because the create response is not where it is needed. A client whose create
 * succeeded but whose response was lost still has the card queued locally, and the next listing
 * returns that card under a server id it has never seen; without the key it cannot tell its own
 * card from a new one and shows the user both. Every response uses this one shape, so echoing it
 * here is what puts it on the listing.
 */
public record CardResponse(
        Long id,
        Long topicId,
        String front,
        String back,
        double easeFactor,
        int intervalDays,
        int repetitions,
        int lapses,
        LocalDate dueDate,
        Instant lastReviewedAt,
        boolean archived,
        UUID clientCardId) {

    public static CardResponse from(Card card) {
        return new CardResponse(
                card.getId(),
                // The proxy answers this from the foreign key already on the card row, so it
                // does not initialise and no session is needed. Reading any other field of the
                // topic here would throw — verified by trying it.
                card.getTopic().getId(),
                card.getFront(),
                card.getBack(),
                card.getEaseFactor(),
                card.getIntervalDays(),
                card.getRepetitions(),
                card.getLapses(),
                card.getDueDate(),
                card.getLastReviewedAt(),
                card.isArchived(),
                card.getClientCardId());
    }
}
