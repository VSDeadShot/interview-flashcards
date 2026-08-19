package dev.vsdeadshot.flashcards.ui.cards;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import androidx.recyclerview.widget.RecyclerView;
import dev.vsdeadshot.flashcards.R;
import dev.vsdeadshot.flashcards.data.local.CandidateEntity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * The generated batch as it appears on the card list — a band above the saved cards, with the
 * two decisions that empty it.
 *
 * <p>The part worth pinning beyond assembly is that accepting goes through the ordinary authoring
 * path: a candidate becomes an unsent card the outbox will send, not a row written straight into
 * the deck. Nothing else on this screen can check that, because nothing else writes cards.
 */
public class CandidateBandTest extends CardListTestSupport {

    @Test
    public void candidatesAppearAboveSavedCardsWithACountHeader() throws Exception {
        cachePulledCard(1L, "What does ACID stand for?");
        cacheCandidates("Q1", "Q2");

        RecyclerView list = openCardsTab();

        assertEquals("the header should count the batch",
                "2 generated — review before saving",
                text(list, 0, R.id.candidate_header_text));
        assertEquals("the first candidate should sit directly under the header",
                "Q1", text(list, 1, R.id.candidate_front));
        assertEquals("saved cards come after the band",
                "What does ACID stand for?", text(list, 3, R.id.card_front));
    }

    @Test
    public void acceptingACandidateMovesItIntoTheDeck() throws Exception {
        cacheCandidates("Q1", "Q2");
        RecyclerView list = openCardsTab();

        row(list, 1).findViewById(R.id.candidate_accept).performClick();
        settle();

        assertEquals("the band should be one shorter", 1, db.candidates().count());
        assertEquals("the accepted candidate should now be a card",
                1, db.cards().pendingCreates().size());
        assertEquals("the card should carry the candidate's question",
                "Q1", db.cards().pendingCreates().get(0).front);
    }

    @Test
    public void discardingACandidateRemovesItWithoutCreatingACard() throws Exception {
        cacheCandidates("Q1", "Q2");
        RecyclerView list = openCardsTab();

        row(list, 1).findViewById(R.id.candidate_discard).performClick();
        settle();

        assertEquals("the band should be one shorter", 1, db.candidates().count());
        assertEquals("discarding must not write a card", 0, db.cards().pendingCreates().size());
    }

    @Test
    public void discardingAllEmptiesTheBandInOneGo() throws Exception {
        cacheCandidates("Q1", "Q2", "Q3");
        RecyclerView list = openCardsTab();

        row(list, 0).findViewById(R.id.candidate_discard_all).performClick();
        settle();

        assertEquals("a batch nobody wants should take one tap to be rid of",
                0, db.candidates().count());
        assertEquals("and none of them should have become a card",
                0, db.cards().pendingCreates().size());
    }

    /**
     * Absent, not empty. Until somebody generates a batch this screen has to look exactly as it
     * did before the feature existed.
     */
    @Test
    public void anEmptyBandDoesNotShowTheHeaderAtAll() throws Exception {
        cachePulledCard(1L, "What does ACID stand for?");

        RecyclerView list = openCardsTab();

        assertEquals("only the saved card should be listed", 1, list.getAdapter().getItemCount());
        assertNull("there should be no header view to find",
                row(list, 0).findViewById(R.id.candidate_header_text));
        assertEquals("What does ACID stand for?", text(list, 0, R.id.card_front));
    }

    private void cacheCandidates(String... fronts) {
        List<CandidateEntity> batch = new ArrayList<>();
        for (String front : fronts) {
            CandidateEntity candidate = new CandidateEntity();
            candidate.topicId = 1L;
            candidate.front = front;
            candidate.back = "An answer";
            candidate.generatedAt = Instant.parse("2026-08-18T10:00:00Z");
            batch.add(candidate);
        }
        db.candidates().insertAll(batch);
    }
}
