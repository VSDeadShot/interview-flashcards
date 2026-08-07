package dev.vsdeadshot.flashcards.data.local;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;
import java.time.Instant;

/**
 * A topic as the server sent it. The primary key is the server's id, not a local one: topics
 * are only ever created online, so there is no moment when a topic exists here without one.
 */
@Entity(tableName = "topic")
public class TopicEntity {

    @PrimaryKey
    public long id;

    @NonNull
    public String name = "";

    @NonNull
    public String slug = "";

    public Instant createdAt;
}
