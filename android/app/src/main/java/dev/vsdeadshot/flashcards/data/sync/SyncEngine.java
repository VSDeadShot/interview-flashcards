package dev.vsdeadshot.flashcards.data.sync;

import android.util.Log;
import dev.vsdeadshot.flashcards.data.Mappers;
import dev.vsdeadshot.flashcards.data.local.CardEntity;
import dev.vsdeadshot.flashcards.data.local.FlashcardsDatabase;
import dev.vsdeadshot.flashcards.data.local.PendingReviewEntity;
import dev.vsdeadshot.flashcards.data.remote.ApiException;
import dev.vsdeadshot.flashcards.data.remote.ApiException.Disposition;
import dev.vsdeadshot.flashcards.data.remote.FlashcardsApi;
import dev.vsdeadshot.flashcards.data.remote.dto.CardDto;
import dev.vsdeadshot.flashcards.data.remote.dto.TopicDto;
import dev.vsdeadshot.flashcards.data.sync.SyncResult.Outcome;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import retrofit2.Call;
import retrofit2.Response;

/**
 * Reconciles the cache with the server: the outbox goes up, then everything comes back down.
 *
 * <p>Push first, and not for tidiness. A pull run first would fetch the server's row for every
 * card whose review is still queued — rows it is about to invalidate — and would leave a window
 * where a card the user has already answered is showing as due again.
 *
 * <p>Nothing here knows about threads or schedules. {@link #sync()} blocks, and the class it
 * blocks on behalf of is a WorkManager worker, which is also where a guard against two
 * concurrent runs belongs: unique work is one line there rather than a second mechanism here
 * that would then have to agree with it.
 */
public final class SyncEngine {

    private static final String TAG = "SyncEngine";

    private final FlashcardsApi api;
    private final FlashcardsDatabase db;

    public SyncEngine(FlashcardsApi api, FlashcardsDatabase db) {
        this.api = api;
        this.db = db;
    }

    public SyncResult sync() {
        Push push = push();
        if (push.stopped) {
            return new SyncResult(Outcome.STOPPED, push.pushed, push.dropped, push.stalled, 0, 0);
        }
        try {
            Pull pull = pull();
            return new SyncResult(
                    Outcome.OK, push.pushed, push.dropped, push.stalled, pull.topics, pull.cards);
        } catch (IOException failure) {
            Outcome outcome = ApiException.dispositionOf(failure) == Disposition.STOP
                    ? Outcome.STOPPED
                    : Outcome.FAILED;
            return new SyncResult(outcome, push.pushed, push.dropped, push.stalled, 0, 0);
        }
    }

    /**
     * Drains the outbox, one card at a time.
     *
     * <p>The rows come back in {@code id} order and are replayed in it, but grouped by card
     * first. The server's ordering rule is per card — it refuses a review older than that
     * card's last one — so a strict global order is stricter than the server asks for, and it
     * would let one card the server keeps refusing hold up every review queued behind it.
     * Grouped, a stuck card stalls only itself.
     */
    private Push push() {
        int pushed = 0;
        int dropped = 0;
        int stalled = 0;

        for (List<PendingReviewEntity> chain : queuedByCard().values()) {
            // The last answer the server gave for this card, held back rather than written as
            // it arrives. Writing each one would step the card through the schedules of the
            // reviews accepted so far, and a chain that stalls half way would leave the card
            // showing a state earlier than the prediction the user was just looking at.
            CardDto confirmed = null;
            boolean drained = true;

            for (PendingReviewEntity review : chain) {
                try {
                    confirmed = execute(api.review(review.cardId, Mappers.toRequest(review)));
                    db.pendingReviews().delete(review);
                    pushed++;
                } catch (IOException failure) {
                    Disposition disposition = ApiException.dispositionOf(failure);
                    if (disposition == Disposition.STOP) {
                        // Everything still queued is stalled, not just the rest of this chain:
                        // the key that was refused here would be refused by every card's
                        // requests, including the chains this loop has not reached.
                        return new Push(pushed, dropped, db.pendingReviews().size(), true);
                    }
                    if (disposition == Disposition.RETRY) {
                        db.pendingReviews().recordFailure(review.id, failure.getMessage());
                        stalled += remaining(chain, review);
                        drained = false;
                        break;
                    }
                    // Permanent. The row goes, because a review the server will never accept
                    // would otherwise keep this card out of every future pull — and a card
                    // frozen forever is worse than a lost answer. The reason is logged and
                    // counted rather than kept on the row, since the row is what has to go.
                    Log.w(TAG, "Dropping review " + review.clientReviewId + " of card "
                            + review.cardId + ": " + failure.getMessage());
                    db.pendingReviews().delete(review);
                    dropped++;
                }
            }

            // Only once nothing is queued for this card. Still-queued rows mean the card's
            // local prediction is the newer truth, and the pull leaves it alone for the same
            // reason. If every row was dropped there is no answer to write, and the card is now
            // absent from cardIdsAwaitingSync() — so the pull below repairs it.
            if (drained && confirmed != null) {
                db.cards().upsertAll(List.of(Mappers.toEntity(confirmed)));
            }
        }
        return new Push(pushed, dropped, stalled, false);
    }

    private Map<Long, List<PendingReviewEntity>> queuedByCard() {
        Map<Long, List<PendingReviewEntity>> byCard = new LinkedHashMap<>();
        for (PendingReviewEntity review : db.pendingReviews().queued()) {
            byCard.computeIfAbsent(review.cardId, cardId -> new ArrayList<>()).add(review);
        }
        return byCard;
    }

    /** How many of a card's chain are left once this one has failed, itself included. */
    private static int remaining(List<PendingReviewEntity> chain, PendingReviewEntity failed) {
        return chain.size() - chain.indexOf(failed);
    }

    /**
     * Replaces the cache with what the server has.
     *
     * <p>The two requests happen outside any transaction — a database held open for the length
     * of a network round trip would block every read the UI makes. What is transactional is the
     * write, so the cache is never half a pull behind.
     */
    private Pull pull() throws IOException {
        List<TopicDto> topics = execute(api.topics());
        // includeArchived, because a card that merely stopped being listed is indistinguishable
        // from one that was archived, and only one of those means "drop the local copy".
        List<CardDto> cards = execute(api.cards(null, true));

        int[] written = new int[2];
        db.runInTransaction(() -> {
            written[0] = writeTopics(topics);
            written[1] = writeCards(cards);
        });
        return new Pull(written[0], written[1]);
    }

    private int writeTopics(List<TopicDto> dtos) {
        if (dtos.isEmpty()) {
            // Room expands an empty list to `not in ()`, which SQLite rejects outright, so the
            // no-topics case cannot go through deleteMissing at all.
            db.topics().deleteAll();
            return 0;
        }
        db.topics().upsertAll(Mappers.toTopicEntities(dtos));
        List<Long> serverIds = new ArrayList<>(dtos.size());
        for (TopicDto dto : dtos) {
            serverIds.add(dto.id);
        }
        db.topics().deleteMissing(serverIds);
        return dtos.size();
    }

    private int writeCards(List<CardDto> dtos) {
        if (dtos.isEmpty()) {
            db.cards().deleteAll();
            return 0;
        }
        // Read inside the transaction, not before the requests went out. A review enqueued
        // while the network was busy would otherwise not be in a list taken earlier, and its
        // card would be overwritten by the server's row from before that review — losing the
        // prediction the user is looking at and putting the card back in today's queue.
        Set<Long> awaitingSync = new HashSet<>(db.pendingReviews().cardIdsAwaitingSync());

        List<CardEntity> toWrite = new ArrayList<>(dtos.size());
        List<Long> serverIds = new ArrayList<>(dtos.size());
        for (CardDto dto : dtos) {
            // Every card the server listed, including the skipped ones. They exist; leaving
            // them out would have deleteMissing delete exactly the cards with unsent work.
            serverIds.add(dto.id);
            if (!awaitingSync.contains(dto.id)) {
                toWrite.add(Mappers.toEntity(dto));
            }
        }
        db.cards().upsertAll(toWrite);
        db.cards().deleteMissing(serverIds);
        return toWrite.size();
    }

    /**
     * The body of a successful call. {@code ProblemInterceptor} has already turned every non-2xx
     * into an {@link ApiException}, so there is no unsuccessful response to test for here — only
     * a success with nothing in it, which is a malformed answer rather than a value to use.
     */
    private static <T> T execute(Call<T> call) throws IOException {
        Response<T> response = call.execute();
        T body = response.body();
        if (body == null) {
            throw new IOException("Empty body from " + call.request().url());
        }
        return body;
    }

    private record Push(int pushed, int dropped, int stalled, boolean stopped) {
    }

    private record Pull(int topics, int cards) {
    }
}
