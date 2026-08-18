package dev.vsdeadshot.flashcards.data;

import dev.vsdeadshot.flashcards.data.local.CandidateEntity;
import dev.vsdeadshot.flashcards.data.local.CardEntity;
import dev.vsdeadshot.flashcards.data.local.FlashcardsDatabase;
import dev.vsdeadshot.flashcards.data.remote.FlashcardsApi;
import dev.vsdeadshot.flashcards.data.remote.dto.CandidateDto;
import dev.vsdeadshot.flashcards.data.remote.dto.GenerateRequestDto;
import dev.vsdeadshot.flashcards.data.remote.dto.GenerateResponseDto;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
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
    private final FlashcardsApi api;
    private final Clock clock;

    /**
     * For a caller that only reads, accepts and discards. Leaves no API, which is correct: those
     * three things never touch a network, and a caller that cannot generate should not have to
     * supply the means to.
     */
    public CandidateRepository(FlashcardsDatabase db) {
        this(db, null, Clock.systemDefaultZone());
    }

    public CandidateRepository(FlashcardsDatabase db, FlashcardsApi api, Clock clock) {
        this.db = db;
        this.cards = new CardRepository(db, clock);
        this.api = api;
        this.clock = clock;
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

    /**
     * Asks the server for a batch and stores it.
     *
     * <p>Foreground work, and deliberately not outbox work. This runs because a person pressed a
     * button and is watching, so a failure is theirs to see and decide about rather than something
     * queued and retried behind them. Nothing here touches {@code SyncEngine}, and a candidate
     * never enters {@code pending_review}.
     *
     * <p>It is also the one thing in this app that cannot work offline. Everything else was built
     * so that the radio being off changes nothing; a model has to be asked.
     *
     * @return how many candidates were stored
     * @throws dev.vsdeadshot.flashcards.data.remote.ApiException if the server refused
     * @throws IOException if the request never got there
     */
    public int generate(long topicId, String focus, int count) throws IOException {
        GenerateRequestDto body = new GenerateRequestDto();
        body.topicId = topicId;
        body.focus = focus;
        body.count = count;

        GenerateResponseDto response = api.generate(body).execute().body();
        List<CandidateEntity> batch = new ArrayList<>();
        if (response != null && response.candidates != null) {
            for (CandidateDto dto : response.candidates) {
                CandidateEntity candidate = new CandidateEntity();
                candidate.topicId = topicId;
                candidate.front = dto.front;
                candidate.back = dto.back;
                candidate.generatedAt = clock.instant();
                batch.add(candidate);
            }
        }
        store(topicId, batch);
        return batch.size();
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
