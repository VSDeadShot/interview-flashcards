package dev.vsdeadshot.flashcards.data.local;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;

/**
 * The local cache.
 *
 * <p>Everything the app shows is read from here, so every screen works with the radio off.
 * The network's job is to keep this table set current, not to answer a question a screen
 * asked.
 *
 * <p><strong>This is a cache, with one exception.</strong> {@code topic} and {@code card} can
 * be thrown away and pulled again at any time; {@code pending_review} cannot, because it is
 * the only record that a review happened. That asymmetry is why a schema change may take the
 * destructive path for the first two and never for the outbox — and why the outbox is written
 * before the card it belongs to is touched.
 */
@Database(
        entities = {TopicEntity.class, CardEntity.class, PendingReviewEntity.class},
        version = 1,
        exportSchema = true)
@TypeConverters(Converters.class)
public abstract class FlashcardsDatabase extends RoomDatabase {

    public abstract TopicDao topics();

    public abstract CardDao cards();

    public abstract PendingReviewDao pendingReviews();

    private static volatile FlashcardsDatabase instance;

    public static FlashcardsDatabase get(Context context) {
        if (instance == null) {
            synchronized (FlashcardsDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    FlashcardsDatabase.class,
                                    "flashcards.db")
                            .build();
                }
            }
        }
        return instance;
    }
}
