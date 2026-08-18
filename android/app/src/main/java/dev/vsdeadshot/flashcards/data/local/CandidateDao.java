package dev.vsdeadshot.flashcards.data.local;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import java.util.List;

@Dao
public interface CandidateDao {

    @Insert
    void insertAll(List<CandidateEntity> candidates);

    /** Insertion order, which is the order the model produced them in. */
    @Query("select * from candidate order by id")
    List<CandidateEntity> all();

    @Query("select * from candidate where id = :id")
    CandidateEntity find(long id);

    @Query("delete from candidate where id = :id")
    void delete(long id);

    @Query("delete from candidate")
    void deleteAll();

    @Query("select count(*) from candidate")
    int count();
}
