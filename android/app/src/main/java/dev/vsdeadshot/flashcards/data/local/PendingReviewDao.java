package dev.vsdeadshot.flashcards.data.local;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import java.util.List;

@Dao
public interface PendingReviewDao {

    @Insert
    long enqueue(PendingReviewEntity review);

    /** Oldest first, which is the order the server will accept them in. */
    @Query("select * from pending_review order by id asc")
    List<PendingReviewEntity> queued();

    @Query("select count(*) from pending_review")
    int size();

    /**
     * The cards a pull must not overwrite: their local schedule is a prediction the server has
     * not seen yet, and the row it would send back is the one from before the review.
     */
    @Query("select distinct cardId from pending_review")
    List<Long> cardIdsAwaitingSync();

    @Delete
    void delete(PendingReviewEntity review);

    /**
     * Discards a card's queued reviews along with the card.
     *
     * <p>Only for a card the server was never told about. Its reviews name a card that will never
     * exist there, so they can never be sent — and left behind they would be counted as
     * outstanding on every run for the rest of this database's life.
     */
    @Query("delete from pending_review where cardId = :cardId")
    void deleteForCard(long cardId);

    /** Records a failed attempt, so a queue that is stuck can say why rather than just retrying. */
    @Query("update pending_review set attempts = attempts + 1, lastError = :detail where id = :id")
    void recordFailure(long id, String detail);
}
