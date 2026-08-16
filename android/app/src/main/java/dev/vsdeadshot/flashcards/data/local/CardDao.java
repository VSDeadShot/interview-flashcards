package dev.vsdeadshot.flashcards.data.local;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Dao
public interface CardDao {

    /**
     * What a pull writes. {@code REPLACE} because the server is the authority on every column
     * here — a pull is not a merge.
     *
     * <p>It must not run over a card with a review still in the outbox: the server's row still
     * holds the schedule from before that review, so replacing would undo the local prediction
     * and put the card back in today's queue. {@code SyncEngine} excludes those; this method is
     * deliberately blunt so that decision lives in one place rather than in a WHERE clause here.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertAll(List<CardEntity> cards);

    /** A card written on this device. Never {@code REPLACE} — a create may not overwrite. */
    @Insert
    void insert(CardEntity card);

    /** Writes back a locally predicted schedule, or the server's answer to a queued review. */
    @Update
    void update(CardEntity card);

    /**
     * The lowest id in the table, or null when it is empty. Local ids are minted below it, so a
     * card written here is negative and a card from the server is positive — the sign is not
     * load-bearing, {@code serverId is null} is, but it makes the two obvious at a glance.
     */
    @Query("select min(id) from card")
    Long lowestId();

    /**
     * The study queue, answered entirely from this database — which is the point of the cache.
     *
     * <p>Ordered {@code dueDate, id} to match the server's own ordering, so the queue a user
     * sees offline is the queue they would have been given online. A card written on this device
     * sorts first among the ones due that day, its id being negative — the server has no opinion
     * about a card it has not seen, so there is nothing to disagree with.
     */
    @Query("""
            select * from card
            where archived = 0 and dueDate <= :today
            order by dueDate asc, id asc
            limit :limit
            """)
    List<CardEntity> queue(LocalDate today, int limit);

    @Query("select * from card where id = :id")
    CardEntity findById(long id);

    /**
     * Cards written on this device that the server has not been told about, oldest first.
     *
     * <p>Local ids run downwards, so {@code id desc} is creation order — the card written first
     * has the id closest to zero. Rows carrying a {@code syncError} are left out: the server has
     * already refused them in a way that will repeat, and sending them again every sync would
     * spend the battery re-deriving the same answer.
     */
    @Query("select * from card where serverId is null and syncError is null order by id desc")
    List<CardEntity> pendingCreates();

    /** How many of the above there are, for the tally a run reports. */
    @Query("select count(*) from card where serverId is null and syncError is null")
    int pendingCreateCount();

    /** The local row holding this server id, or null when the server id is new here. */
    @Query("select id from card where serverId = :serverId")
    Long localIdForServerId(long serverId);

    /**
     * The local row this key was minted for, or null when it was minted somewhere else.
     *
     * <p>This is the lookup that repairs a create whose response was lost: the card comes back
     * from a listing under a server id this client has never seen, and the key is the only thing
     * on it that names the row already sitting here.
     */
    @Query("select id from card where clientCardId = :clientCardId")
    Long localIdForClientCardId(UUID clientCardId);

    /** Parks a card the server will refuse again, with the reason it gave. */
    @Query("update card set syncError = :detail where id = :id")
    void recordSyncFailure(long id, String detail);

    /**
     * Records what the server said about a card it has just created, without touching a single
     * field the user can type into.
     *
     * <p>The echo carries no content this client did not send a moment ago, so writing the whole
     * row back would only make it possible to lose an edit made while the request was in flight.
     * What is genuinely the server's to say is the id and the schedule.
     */
    @Query("""
            update card set serverId = :serverId, easeFactor = :easeFactor,
                intervalDays = :intervalDays, repetitions = :repetitions, lapses = :lapses,
                dueDate = :dueDate, lastReviewedAt = :lastReviewedAt, syncError = null
            where id = :id
            """)
    void recordCreated(long id, long serverId, double easeFactor, int intervalDays,
            int repetitions, int lapses, LocalDate dueDate, Instant lastReviewedAt);

    /**
     * Writes back the schedule a review produced, and nothing else.
     *
     * <p>Narrow for the same reason {@link #recordCreated} is. A review's answer is the whole
     * card, but the only part of it this client did not already know is the schedule — and
     * writing the rest back would undo an edit or an archive the user has made and the sync has
     * not sent yet, along with the marker saying it still needs sending.
     */
    @Query("""
            update card set easeFactor = :easeFactor, intervalDays = :intervalDays,
                repetitions = :repetitions, lapses = :lapses, dueDate = :dueDate,
                lastReviewedAt = :lastReviewedAt
            where id = :id
            """)
    void recordSchedule(long id, double easeFactor, int intervalDays, int repetitions,
            int lapses, LocalDate dueDate, Instant lastReviewedAt);

    /**
     * Rows the server has, whose local copy differs from it. An archived one means a
     * {@code DELETE} and any other a {@code PUT}; a card carrying a {@code syncError} is left out
     * for the same reason a refused create is.
     */
    @Query("""
            select * from card
            where pendingSince is not null and serverId is not null and syncError is null
            order by pendingSince asc, id asc
            """)
    List<CardEntity> pendingSyncs();

    /**
     * Clears the marker, but only if the row still holds what was sent.
     *
     * <p>The comparison is the point. A plain clear would drop the marker on content the user
     * changed while the request was in flight, and that edit would then never be sent — the
     * failure being silent, since the row looks synced. Unmatched here, the marker stays and the
     * next run sends again.
     */
    @Query("""
            update card set pendingSince = null
            where id = :id and front = :front and back = :back and topicId = :topicId
            """)
    void clearPendingIfUnchanged(long id, String front, String back, long topicId);

    /** Local ids whose row must not be overwritten by a pull, whatever kind of work is unsent. */
    @Query("""
            select cardId from pending_review
            union
            select id from card where pendingSince is not null
            """)
    List<Long> localIdsWithUnsentWork();

    /** For a card the server was never told about, which therefore needs no telling. */
    @Query("delete from card where id = :id")
    void deleteById(long id);

    /**
     * Every card in circulation, with its topic name already joined on.
     *
     * <p>A <strong>left</strong> join, not an inner one: a pull can write a card before its topic
     * and a topic can be deleted on the server, and neither is a reason for a card to vanish from
     * the one screen that is supposed to show all of them. The name comes back null instead.
     *
     * <p>Joined here rather than looked up per card, because this is the one query that returns
     * every card at once and a lookup each would be the N+1 in its most literal form.
     *
     * <p>Ordered by topic, then by id ascending. Local ids run downwards from zero, so within a
     * topic the most recently written card sorts first — which is where somebody who has just
     * added one will look for it.
     */
    @Query("""
            select c.id as id, c.front as front, t.name as topicName,
                   c.serverId as serverId, c.pendingSince as pendingSince,
                   c.syncError as syncError
            from card c left join topic t on t.id = c.topicId
            where c.archived = 0
            order by t.name asc, c.id asc
            """)
    List<CardSummaryRow> summaries();

    @Query("select * from card where archived = 0 order by id asc")
    List<CardEntity> findAllActive();

    @Query("select count(*) from card where archived = 0 and dueDate <= :today")
    int countDue(LocalDate today);

    /**
     * Removes cards the server no longer lists. A pull fetches every card including archived
     * ones, so anything absent from that answer is gone for good and keeping it would leave the
     * cache with rows nothing can explain.
     *
     * <p>Scoped to rows that have a {@code serverId}. A card written on this device has not been
     * offered to the server yet, so of course the server did not list it — deleting it here
     * would throw away what the user typed, on the first sync after they typed it.
     */
    @Query("delete from card where serverId is not null and serverId not in (:serverIds)")
    void deleteMissing(List<Long> serverIds);

    /** The same exclusion, for a server that lists no cards at all. */
    @Query("delete from card where serverId is not null")
    void deleteAllFromServer();
}
