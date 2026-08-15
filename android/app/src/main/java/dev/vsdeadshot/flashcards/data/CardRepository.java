package dev.vsdeadshot.flashcards.data;

import dev.vsdeadshot.flashcards.data.local.CardEntity;
import dev.vsdeadshot.flashcards.data.local.FlashcardsDatabase;
import dev.vsdeadshot.flashcards.scheduler.SchedulingState;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Writing and editing cards, with or without a network.
 *
 * <p>A card written here is real immediately: it goes into the same table every screen reads,
 * with the same starting schedule the server would have given it, so it can be studied in the
 * session it was written in. What it does not have is a {@code serverId} — that arrives when a
 * sync creates it, and until then this row is the only copy of it.
 *
 * <p>The {@code clientCardId} minted here is the whole reason the create is safe to retry. It
 * is written with the card rather than at send time, so every attempt carries the same value
 * and a create whose response was lost is recognised rather than repeated.
 */
public final class CardRepository {

    private final FlashcardsDatabase db;
    private final Clock clock;

    public CardRepository(FlashcardsDatabase db) {
        this(db, Clock.systemDefaultZone());
    }

    public CardRepository(FlashcardsDatabase db, Clock clock) {
        this.db = Objects.requireNonNull(db, "db must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Writes a new card into the cache and returns it.
     *
     * @throws IllegalArgumentException if the topic is not one this device knows about, or if
     *     either side of the card is blank
     */
    public CardEntity create(long topicId, String front, String back) {
        String question = require(front, "front");
        String answer = require(back, "back");
        // Due today rather than tomorrow, matching the server, so a card added during a session
        // can be studied in it.
        SchedulingState initial = SchedulingState.newCard(LocalDate.now(clock));
        UUID clientCardId = UUID.randomUUID();

        return db.runInTransaction(() -> {
            if (db.topics().findById(topicId) == null) {
                throw new IllegalArgumentException("No cached topic with id " + topicId);
            }

            CardEntity card = new CardEntity();
            card.id = nextLocalId();
            card.clientCardId = clientCardId;
            card.topicId = topicId;
            card.front = question;
            card.back = answer;
            card.easeFactor = initial.easeFactor();
            card.intervalDays = initial.intervalDays();
            card.repetitions = initial.repetitions();
            card.lapses = initial.lapses();
            card.dueDate = initial.dueDate();
            db.cards().insert(card);
            return card;
        });
    }

    /**
     * Replaces the text and the topic. The schedule is untouched, exactly as on the server —
     * correcting a typo must not reset a card's progress.
     *
     * <p>Marks the row as differing from the server's copy, whether or not the server has one
     * yet. For a card still waiting to be created the marker is redundant, since the create will
     * carry the new text anyway — and it costs nothing, because a create that goes out with this
     * content clears the marker on the way past. Being unconditional is what makes an edit landing
     * mid-create safe: the create clears the marker only if the row still holds what it sent.
     *
     * <p>Editing also clears a {@code syncError}, which is the whole recovery path for a card
     * the server refused: it says why, the user fixes it, and the card is offered again.
     */
    public CardEntity edit(long localId, long topicId, String front, String back) {
        String question = require(front, "front");
        String answer = require(back, "back");
        Instant editedAt = clock.instant();

        return db.runInTransaction(() -> {
            CardEntity card = db.cards().findById(localId);
            if (card == null) {
                throw new IllegalArgumentException("No cached card with id " + localId);
            }
            if (db.topics().findById(topicId) == null) {
                throw new IllegalArgumentException("No cached topic with id " + topicId);
            }
            card.topicId = topicId;
            card.front = question;
            card.back = answer;
            card.syncError = null;
            card.pendingSince = editedAt;
            db.cards().update(card);
            return card;
        });
    }

    /**
     * Retires a card. The server archives rather than deleting, so history survives there, and
     * this marks the row the same way and lets the sync send the {@code DELETE}.
     *
     * <p>A card the server was never told about is the exception: there is nothing to tell it, so
     * the row goes outright. Its queued reviews go with it — they name a card that will never
     * exist on the server, so they could never be sent, and left behind they would be counted as
     * outstanding work on every run from then on.
     */
    public void archive(long localId) {
        Instant archivedAt = clock.instant();

        db.runInTransaction(() -> {
            CardEntity card = db.cards().findById(localId);
            if (card == null) {
                throw new IllegalArgumentException("No cached card with id " + localId);
            }
            if (card.serverId == null) {
                db.pendingReviews().deleteForCard(localId);
                db.cards().deleteById(localId);
                return;
            }
            card.archived = true;
            card.syncError = null;
            card.pendingSince = archivedAt;
            db.cards().update(card);
        });
    }

    /**
     * One below the lowest id in the table, so a card written here is negative and cannot
     * collide with a server id arriving in a later pull. Read inside the caller's transaction,
     * which is what stops two creates in flight from choosing the same one.
     */
    private long nextLocalId() {
        Long lowest = db.cards().lowestId();
        return lowest == null || lowest >= 0 ? -1 : lowest - 1;
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
