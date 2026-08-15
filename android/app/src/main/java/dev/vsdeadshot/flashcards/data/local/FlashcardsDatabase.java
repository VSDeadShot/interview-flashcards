package dev.vsdeadshot.flashcards.data.local;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

/**
 * The local cache.
 *
 * <p>Everything the app shows is read from here, so every screen works with the radio off.
 * The network's job is to keep this table set current, not to answer a question a screen
 * asked.
 *
 * <p><strong>This is a cache, with two exceptions.</strong> {@code topic} can be thrown away
 * and pulled again at any time. {@code pending_review} cannot, because it is the only record
 * that a review happened. Neither can a {@code card} row with no {@code serverId}: that is a
 * card written on this device that no sync has created yet, and nothing can reconstruct it.
 * So a schema change may take the destructive path for {@code topic} alone — and the outbox is
 * written before the card it belongs to is touched.
 */
@Database(
        entities = {TopicEntity.class, CardEntity.class, PendingReviewEntity.class},
        version = 2,
        exportSchema = true)
@TypeConverters(Converters.class)
public abstract class FlashcardsDatabase extends RoomDatabase {

    public abstract TopicDao topics();

    public abstract CardDao cards();

    public abstract PendingReviewDao pendingReviews();

    /**
     * Adds the local id and the create key.
     *
     * <p>Two {@code alter table}s and an update rather than a table rebuild, which matters more
     * than it looks: a rebuild recreates {@code card} with new row ids, and every queued review
     * points at one. Every card in a version 1 database came from the server and was stored
     * under the server's own id, so that id is both the local id and the server id here — which
     * is exactly what leaves {@code pending_review} correct without touching it.
     */
    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("alter table card add column serverId INTEGER");
            db.execSQL("alter table card add column clientCardId TEXT");
            db.execSQL("update card set serverId = id");
            db.execSQL(
                    "create unique index if not exists index_card_serverId on card (serverId)");
            db.execSQL("create unique index if not exists index_card_clientCardId"
                    + " on card (clientCardId)");
        }
    };

    private static volatile FlashcardsDatabase instance;

    public static FlashcardsDatabase get(Context context) {
        if (instance == null) {
            synchronized (FlashcardsDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    FlashcardsDatabase.class,
                                    "flashcards.db")
                            // No destructive fallback. An unsynced card lives in this file and
                            // nowhere else, so a missing migration must fail loudly rather than
                            // quietly discard what the user wrote.
                            .addMigrations(MIGRATION_1_2)
                            .build();
                }
            }
        }
        return instance;
    }
}
