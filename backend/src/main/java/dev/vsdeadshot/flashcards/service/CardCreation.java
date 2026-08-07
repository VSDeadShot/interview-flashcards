package dev.vsdeadshot.flashcards.service;

import dev.vsdeadshot.flashcards.domain.Card;

/**
 * The outcome of {@code POST /cards}: the card, and whether this request is what made it.
 *
 * <p>The flag exists so the controller can answer {@code 200} rather than {@code 201} when a
 * retry finds the card its own earlier attempt created. Both return the same body, and the
 * client needs no branch for it — but a {@code 201} would be asserting a creation that did not
 * happen, and the status code is the one part of the response that cannot say "already done".
 *
 * @param replayed true when the card was found by client key rather than created here
 */
public record CardCreation(Card card, boolean replayed) {
}
