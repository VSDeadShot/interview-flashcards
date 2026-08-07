package dev.vsdeadshot.flashcards.data.local;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;

@Dao
public interface TopicDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertAll(List<TopicEntity> topics);

    @Query("select * from topic order by name asc")
    List<TopicEntity> findAll();

    @Query("select * from topic where id = :id")
    TopicEntity findById(long id);

    @Query("delete from topic where id not in (:serverIds)")
    void deleteMissing(List<Long> serverIds);

    @Query("delete from topic")
    void deleteAll();
}
