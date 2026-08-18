package dev.vsdeadshot.flashcards.web.dto;

import dev.vsdeadshot.flashcards.ai.GeneratedCard;

/**
 * One generated candidate on the wire.
 *
 * <p>Deliberately not {@link CardResponse}. That is the only shape a card takes precisely because
 * the client caches cards by id; a candidate has no id, no schedule and no row behind it, and
 * handing back a CardResponse with empty fields would make that the client's problem permanently.
 */
public record CandidateResponse(String front, String back) {

    public static CandidateResponse from(GeneratedCard card) {
        return new CandidateResponse(card.front(), card.back());
    }
}
