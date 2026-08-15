package dev.vsdeadshot.flashcards.data.local;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.time.LocalDate;
import java.util.List;

/**
 * The reads a stats screen makes, and they are all local.
 *
 * <p>Only the streak comes from the server, and only because it cannot be worked out here. The
 * counts are queried from the cache on every read rather than snapshotted, because the one that
 * matters most — how many cards are still due — has to fall as the user works through the queue.
 */
@Dao
public interface StatsDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void saveSnapshot(StatsSnapshotEntity snapshot);

    @Query("select * from stats_snapshot where id = " + StatsSnapshotEntity.ROW_ID)
    StatsSnapshotEntity snapshot();

    /**
     * Adds one to today's tally, creating the day's row the first time it is asked for.
     *
     * <p>An upsert rather than a read and a write, so two reviews answered in quick succession
     * cannot both read the same number and store it back.
     */
    @Query("""
            insert into review_tally (day, reviews) values (:day, 1)
            on conflict(day) do update set reviews = reviews + 1
            """)
    void recordReview(LocalDate day);

    /** Null when nothing has been answered on that day, which the caller reads as zero. */
    @Query("select reviews from review_tally where day = :day")
    Integer reviewsOn(LocalDate day);

    @Query("select count(*) from card where archived = 0")
    int totalCards();

    /**
     * Every topic, including one holding no cards. A topic that vanished from the screen when
     * its last card was archived would look deleted, and nothing deletes topics.
     */
    @Query("""
            select t.id as topicId, t.name as name,
                (select count(*) from card c
                    where c.topicId = t.id and c.archived = 0) as total,
                (select count(*) from card c
                    where c.topicId = t.id and c.archived = 0 and c.dueDate <= :today) as due
            from topic t
            order by t.name asc
            """)
    List<TopicStatsRow> byTopic(LocalDate today);
}
