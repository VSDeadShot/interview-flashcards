package dev.vsdeadshot.flashcards.ui.cards;

import dev.vsdeadshot.flashcards.data.local.CandidateEntity;
import dev.vsdeadshot.flashcards.data.local.CardSummaryRow;

/**
 * One row of the card list, whichever of the three kinds it is.
 *
 * <p>The screen shows two different things in one scrolling list, so the adapter is given one
 * flattened list rather than two. That is what keeps {@code ListAdapter} and its diffing: the
 * list is rebuilt on every Room invalidation, and an adapter holding two lists would have to
 * redraw wholesale on each one, losing the row animations the screen already has.
 *
 * <p>Sealed to write down that these three are the whole set. It does not buy an exhaustiveness
 * check here — this module compiles at Java 17, where pattern matching for {@code switch} is
 * still preview — so the adapter tests each kind with {@code instanceof}. What sealing does buy
 * is that a fourth kind cannot be added from outside this file without the compiler saying so.
 */
public sealed interface CardListItem {

    /** The band's count line. Absent, not empty, when there are no candidates. */
    record Header(int count) implements CardListItem {
    }

    record Candidate(CandidateEntity candidate) implements CardListItem {
    }

    record Card(CardSummaryRow card) implements CardListItem {
    }
}
