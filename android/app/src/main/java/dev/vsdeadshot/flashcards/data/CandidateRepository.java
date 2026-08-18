package dev.vsdeadshot.flashcards.data;

import dev.vsdeadshot.flashcards.data.local.CandidateEntity;
import dev.vsdeadshot.flashcards.data.local.CardEntity;
import dev.vsdeadshot.flashcards.data.local.FlashcardsDatabase;
import java.util.List;

/**
 * The generated batch waiting to be reviewed.
 *
 * <p>Blocking and composed, like every other repository here: a view model runs it on
 * {@code Graph.io()} and publishes the result.
 */
public final class CandidateRepository {

    private final FlashcardsDatabase db;
    private final CardRepository cards;

    public CandidateRepository(FlashcardsDatabase db) {
        this.db = db;
        this.cards = new CardRepository(db);
    }

    /**
     * Replaces whatever was there. One batch at a time is deliberate: the band is a review queue,
     * not a history, and an unbounded one would quietly turn into a second and worse card list.
     */
    public void store(long topicId, List<CandidateEntity> candidates) {
        db.runInTransaction(() -> {
            db.candidates().deleteAll();
            db.candidates().insertAll(candidates);
        });
    }

    public List<CandidateEntity> all() {
        return db.candidates().all();
    }

    /**
     * Turns a candidate into a card by the ordinary authoring path, so from this moment it is an
     * ordinary unsynced card: it carries a client id, the outbox offers it as a pending create,
     * and none of that needed a network.
     *
     * <p>Both halves run in one transaction. A card written without its candidate removed would
     * be offered for review a second time, and the user would have no way to tell it had already
     * been accepted.
     *
     * @return the card, or null if there was no such candidate
     */
    public CardEntity accept(long candidateId) {
        return db.runInTransaction(() -> {
            CandidateEntity candidate = db.candidates().find(candidateId);
            if (candidate == null) {
                return null;
            }
            CardEntity created = cards.create(candidate.topicId, candidate.front, candidate.back);
            db.candidates().delete(candidateId);
            return created;
        });
    }

    /** A row delete. There is no discarded column, because nothing would ever read one. */
    public void discard(long candidateId) {
        db.candidates().delete(candidateId);
    }

    public void discardAll() {
        db.candidates().deleteAll();
    }
}
