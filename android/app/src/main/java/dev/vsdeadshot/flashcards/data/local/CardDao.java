package dev.vsdeadshot.flashcards.data.local;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;
import java.time.LocalDate;
import java.util.List;

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

    /** Writes back a locally predicted schedule, or the server's answer to a queued review. */
    @Update
    void update(CardEntity card);

    /**
     * The study queue, answered entirely from this database — which is the point of the cache.
     *
     * <p>Ordered {@code dueDate, id} to match the server's own ordering, so the queue a user
     * sees offline is the queue they would have been given online.
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

    @Query("select * from card where archived = 0 order by id asc")
    List<CardEntity> findAllActive();

    @Query("select count(*) from card where archived = 0 and dueDate <= :today")
    int countDue(LocalDate today);

    /**
     * Removes cards the server no longer lists. A pull fetches every card including archived
     * ones, so anything absent from that answer is gone for good and keeping it would leave the
     * cache with rows nothing can explain.
     */
    @Query("delete from card where id not in (:serverIds)")
    void deleteMissing(List<Long> serverIds);

    @Query("delete from card")
    void deleteAll();
}
