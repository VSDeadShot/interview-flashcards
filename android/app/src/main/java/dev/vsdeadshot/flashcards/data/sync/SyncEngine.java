package dev.vsdeadshot.flashcards.data.sync;

import android.util.Log;
import dev.vsdeadshot.flashcards.data.Mappers;
import dev.vsdeadshot.flashcards.data.local.CardEntity;
import dev.vsdeadshot.flashcards.data.local.FlashcardsDatabase;
import dev.vsdeadshot.flashcards.data.local.PendingReviewEntity;
import dev.vsdeadshot.flashcards.data.local.StatsSnapshotEntity;
import dev.vsdeadshot.flashcards.data.remote.ApiException;
import dev.vsdeadshot.flashcards.data.remote.ApiException.Disposition;
import dev.vsdeadshot.flashcards.data.remote.FlashcardsApi;
import dev.vsdeadshot.flashcards.data.remote.dto.CardDto;
import dev.vsdeadshot.flashcards.data.remote.dto.StatsDto;
import dev.vsdeadshot.flashcards.data.remote.dto.TopicDto;
import dev.vsdeadshot.flashcards.data.sync.SyncResult.Outcome;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import retrofit2.Call;
import retrofit2.Response;

/**
 * Reconciles the cache with the server: everything written here goes up, then everything comes
 * back down.
 *
 * <p>Push first, and not for tidiness. A pull run first would fetch the server's row for every
 * card whose review is still queued — rows it is about to invalidate — and would leave a window
 * where a card the user has already answered is showing as due again.
 *
 * <p>Within the push, cards go before reviews. A review can only be sent against a server id,
 * and a card written on this device has none until its create is accepted, so a card written and
 * studied in the same offline session syncs completely in one run rather than two.
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
    private final Clock clock;

    public SyncEngine(FlashcardsApi api, FlashcardsDatabase db) {
        this(api, db, Clock.systemDefaultZone());
    }

    public SyncEngine(FlashcardsApi api, FlashcardsDatabase db, Clock clock) {
        this.api = api;
        this.db = db;
        this.clock = clock;
    }

    public SyncResult sync() {
        Push push = push();
        if (push.stopped) {
            return result(Outcome.STOPPED, push, 0, 0);
        }
        try {
            Pull pull = pull();
            return result(Outcome.OK, push, pull.topics, pull.cards);
        } catch (IOException failure) {
            Outcome outcome = ApiException.dispositionOf(failure) == Disposition.STOP
                    ? Outcome.STOPPED
                    : Outcome.FAILED;
            return result(outcome, push, 0, 0);
        }
    }

    private static SyncResult result(Outcome outcome, Push push, int topics, int cards) {
        return new SyncResult(
                outcome, push.created, push.updated, push.pushed, push.dropped, push.stalled,
                push.blocked, topics, cards);
    }

    private Push push() {
        Creates creates = pushCreates();
        if (creates.stopped) {
            // The key was refused, so nothing else would be accepted either. Everything still
            // outstanding is stalled rather than blocked: a rejected key is not a card the
            // server has an opinion about, and the distinction only matters for work a retry
            // cannot fix on its own.
            return new Push(creates.created, 0, 0, 0, outstanding(), 0, true);
        }
        Reviews reviews = pushReviews();
        if (reviews.stopped) {
            return new Push(creates.created, 0, reviews.pushed, reviews.dropped, outstanding(), 0,
                    true);
        }
        // Last, so a card is never retired before the reviews that happened while it was still
        // in use have been recorded.
        Updates updates = pushUpdates();
        if (updates.stopped) {
            return new Push(creates.created, updates.updated, reviews.pushed, reviews.dropped,
                    outstanding(), 0, true);
        }
        return new Push(
                creates.created,
                updates.updated,
                reviews.pushed,
                reviews.dropped,
                creates.stalled + reviews.stalled + updates.stalled,
                creates.blocked + reviews.blocked + updates.blocked,
                false);
    }

    /**
     * Sends the rows whose local copy differs from the server's — an edit as a {@code PUT}, an
     * archived card as a {@code DELETE}.
     *
     * <p>Independent of one another like creates, so a failure moves on to the next card. What
     * this deliberately does not do is reconcile: if the server's copy also changed, this
     * overwrites it. With one user that is nearly unreachable, and the contract offers no
     * {@code If-Match} to do better — the limitation is written down rather than guessed at.
     */
    private Updates pushUpdates() {
        int updated = 0;
        int stalled = 0;
        int blocked = 0;

        for (CardEntity card : db.cards().pendingSyncs()) {
            try {
                if (card.archived) {
                    executeWithNoAnswer(api.archiveCard(card.serverId));
                } else {
                    execute(api.updateCard(card.serverId, Mappers.toUpdateRequest(card)));
                }
                db.cards().clearPendingIfUnchanged(
                        card.id, card.front, card.back, card.topicId);
                updated++;
            } catch (IOException failure) {
                Disposition disposition = ApiException.dispositionOf(failure);
                if (disposition == Disposition.STOP) {
                    return new Updates(updated, 0, 0, true);
                }
                if (disposition == Disposition.RETRY) {
                    stalled++;
                    continue;
                }
                Log.w(TAG, "Parking update to card " + card.id + " (server "
                        + card.serverId + "): " + failure.getMessage());
                db.cards().recordSyncFailure(card.id, failure.getMessage());
                blocked++;
            }
        }
        return new Updates(updated, stalled, blocked, false);
    }

    /** Everything still waiting to go, ignoring what has been parked. */
    private int outstanding() {
        return db.cards().pendingCreateCount()
                + db.cards().pendingSyncs().size()
                + db.pendingReviews().size();
    }

    /**
     * Offers every card written on this device that the server has not made yet.
     *
     * <p>Unlike a card's reviews these are independent of one another, so a failure moves on to
     * the next card rather than stopping: there is no order between two creates for the server
     * to refuse.
     *
     * <p>A permanent refusal <strong>parks</strong> the card rather than deleting it. This is the
     * opposite of what happens to a review, and for the opposite reason: a review is an event,
     * and dropping it costs one answer while keeping the card moving. A card is the content
     * itself, and there is nothing to reconstruct it from — so the row stays, the server's reason
     * is written on it, and editing the card offers it again.
     */
    private Creates pushCreates() {
        int created = 0;
        int stalled = 0;
        int blocked = 0;

        for (CardEntity card : db.cards().pendingCreates()) {
            try {
                CardDto answer = execute(api.createCard(Mappers.toCreateRequest(card)));
                // Only what the server is actually the authority on. The echo's text is what
                // this client sent a moment ago, so writing the whole row back would gain
                // nothing and would overwrite an edit made while the request was in flight.
                // The local id never moves either — every queued review points at it.
                db.cards().recordCreated(card.id, answer.id, answer.easeFactor,
                        answer.intervalDays, answer.repetitions, answer.lapses, answer.dueDate,
                        answer.lastReviewedAt);
                // And the card is only clean if it still holds what went out; an edit that
                // landed mid-flight leaves the marker up and is sent as an ordinary update.
                db.cards().clearPendingIfUnchanged(card.id, card.front, card.back, card.topicId);
                created++;
            } catch (IOException failure) {
                Disposition disposition = ApiException.dispositionOf(failure);
                if (disposition == Disposition.STOP) {
                    return new Creates(created, 0, 0, true);
                }
                if (disposition == Disposition.RETRY) {
                    stalled++;
                    continue;
                }
                Log.w(TAG, "Parking card " + card.id + " (" + card.clientCardId + "): "
                        + failure.getMessage());
                db.cards().recordSyncFailure(card.id, failure.getMessage());
                blocked++;
            }
        }
        return new Creates(created, stalled, blocked, false);
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
    private Reviews pushReviews() {
        int pushed = 0;
        int dropped = 0;
        int stalled = 0;
        int blocked = 0;

        for (Map.Entry<Long, List<PendingReviewEntity>> entry : queuedByCard().entrySet()) {
            long localCardId = entry.getKey();
            List<PendingReviewEntity> chain = entry.getValue();

            CardEntity card = db.cards().findById(localCardId);
            if (card == null || card.serverId == null) {
                // A review is addressed to a server id, and this card has none. Either its
                // create has not been accepted yet — in which case the next run may fix it — or
                // the server refuses to make it at all, and then no retry of these reviews will
                // ever land.
                if (card == null || card.syncError != null) {
                    blocked += chain.size();
                } else {
                    stalled += chain.size();
                }
                continue;
            }

            // The last answer the server gave for this card, held back rather than written as
            // it arrives. Writing each one would step the card through the schedules of the
            // reviews accepted so far, and a chain that stalls half way would leave the card
            // showing a state earlier than the prediction the user was just looking at.
            CardDto confirmed = null;
            boolean drained = true;

            for (PendingReviewEntity review : chain) {
                try {
                    confirmed = execute(
                            api.review(card.serverId, Mappers.toRequest(review)));
                    db.pendingReviews().delete(review);
                    pushed++;
                } catch (IOException failure) {
                    Disposition disposition = ApiException.dispositionOf(failure);
                    if (disposition == Disposition.STOP) {
                        return new Reviews(pushed, dropped, 0, 0, true);
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
                            + localCardId + ": " + failure.getMessage());
                    db.pendingReviews().delete(review);
                    dropped++;
                }
            }

            // Only once nothing is queued for this card. Still-queued rows mean the card's
            // local prediction is the newer truth, and the pull leaves it alone for the same
            // reason. If every row was dropped there is no answer to write, and the card is now
            // absent from cardIdsAwaitingSync() — so the pull below repairs it.
            if (drained && confirmed != null) {
                // The schedule only. The rest of the answer is what this client already had,
                // and writing it back would undo an edit or an archive that has not gone yet —
                // along with the marker that says it still has to.
                db.cards().recordSchedule(localCardId, confirmed.easeFactor,
                        confirmed.intervalDays, confirmed.repetitions, confirmed.lapses,
                        confirmed.dueDate, confirmed.lastReviewedAt);
            }
        }
        return new Reviews(pushed, dropped, stalled, blocked, false);
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
        refreshStreak();
        return new Pull(written[0], written[1]);
    }

    /**
     * Asks for the one figure this client cannot count for itself.
     *
     * <p>Last, and its failure is not the pull's failure. Topics and cards are the app; the
     * streak is decoration on one screen, and letting a hiccup fetching it turn a completed pull
     * into a failed one would have the worker retry everything it had just finished doing. The
     * previous answer stays, and the "as of" beside it gets older, which is what an "as of" is
     * for.
     */
    private void refreshStreak() {
        try {
            StatsDto stats = execute(api.stats());
            StatsSnapshotEntity snapshot = new StatsSnapshotEntity();
            snapshot.currentStreakDays = stats.currentStreakDays;
            snapshot.fetchedAt = clock.instant();
            db.stats().saveSnapshot(snapshot);
        } catch (IOException failure) {
            Log.w(TAG, "Streak not refreshed: " + failure.getMessage());
        }
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
            db.cards().deleteAllFromServer();
            return 0;
        }
        // Read inside the transaction, not before the requests went out. A review enqueued
        // while the network was busy would otherwise not be in a list taken earlier, and its
        // card would be overwritten by the server's row from before that review — losing the
        // prediction the user is looking at and putting the card back in today's queue.
        //
        // These are local ids, which is why the check below is against the resolved row and not
        // against the id the server sent. One query for every kind of unsent work rather than
        // one per kind, so the next thing that must not be overwritten is added in one place.
        Set<Long> awaitingSync = new HashSet<>(db.cards().localIdsWithUnsentWork());

        List<CardEntity> toWrite = new ArrayList<>(dtos.size());
        List<Long> serverIds = new ArrayList<>(dtos.size());
        for (CardDto dto : dtos) {
            // Every card the server listed, including the skipped ones. They exist; leaving
            // them out would have deleteMissing delete exactly the cards with unsent work.
            serverIds.add(dto.id);

            Long localId = localIdFor(dto);
            if (localId != null && awaitingSync.contains(localId)) {
                continue;
            }
            CardEntity entity = Mappers.toEntity(dto);
            if (localId != null) {
                // Keep the row where it is. A card created here has a local id nothing on the
                // server knows about, and inserting the server's answer under the server's id
                // instead would leave two rows for one card — which is the duplicate the
                // echoed key exists to prevent.
                entity.id = localId;
            }
            toWrite.add(entity);
        }
        db.cards().upsertAll(toWrite);
        db.cards().deleteMissing(serverIds);
        return toWrite.size();
    }

    /**
     * The local row this card belongs in, or null when it is new here.
     *
     * <p>By server id first, which is every card this cache has pulled before. Failing that by
     * the key this device minted, which is the case worth having: the create was accepted and
     * the response never arrived, so the row here still has no server id and the card would
     * otherwise be inserted a second time under one. Resolving it repairs the row instead —
     * including clearing any {@code syncError}, since a card the server has plainly does exist.
     */
    private Long localIdFor(CardDto dto) {
        Long byServerId = db.cards().localIdForServerId(dto.id);
        if (byServerId != null) {
            return byServerId;
        }
        return dto.clientCardId == null
                ? null
                : db.cards().localIdForClientCardId(dto.clientCardId);
    }

    /**
     * The body of a successful call. {@code ProblemInterceptor} has already turned every non-2xx
     * into an {@link ApiException}, so there is no unsuccessful response to test for here — only
     * a success with nothing in it, which is a malformed answer rather than a value to use.
     */
    /**
     * A call whose success has no body. {@code DELETE} answers {@code 204}, so the body is null
     * on the path that worked — running it through {@link #execute} would report every archive
     * the server accepted as a failure worth retrying.
     */
    private static void executeWithNoAnswer(Call<Void> call) throws IOException {
        call.execute();
    }

    private static <T> T execute(Call<T> call) throws IOException {
        Response<T> response = call.execute();
        T body = response.body();
        if (body == null) {
            throw new IOException("Empty body from " + call.request().url());
        }
        return body;
    }

    private record Push(
            int created, int updated, int pushed, int dropped, int stalled, int blocked,
            boolean stopped) {
    }

    private record Updates(int updated, int stalled, int blocked, boolean stopped) {
    }

    private record Creates(int created, int stalled, int blocked, boolean stopped) {
    }

    private record Reviews(
            int pushed, int dropped, int stalled, int blocked, boolean stopped) {
    }

    private record Pull(int topics, int cards) {
    }
}
