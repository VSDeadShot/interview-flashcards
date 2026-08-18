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
 * written before the card it belongs to is touched. {@code stats_snapshot} is a cache of one
 * number; {@code review_tally} is not reconstructable, but losing it only resets a counter that
 * resets at midnight anyway.
 */
@Database(
        entities = {
            TopicEntity.class,
            CardEntity.class,
            PendingReviewEntity.class,
            StatsSnapshotEntity.class,
            ReviewTallyEntity.class,
            CandidateEntity.class
        },
        version = 6,
        exportSchema = true)
@TypeConverters(Converters.class)
public abstract class FlashcardsDatabase extends RoomDatabase {

    public abstract TopicDao topics();

    public abstract CardDao cards();

    public abstract PendingReviewDao pendingReviews();

    public abstract StatsDao stats();

    public abstract CandidateDao candidates();

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

    /**
     * Adds the reason a create was refused. A card that cannot be created is parked rather than
     * deleted, so there has to be somewhere to say why.
     */
    static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("alter table card add column syncError TEXT");
        }
    };

    /**
     * Adds the marker for a row that differs from the server's copy. Nullable, so this is one
     * {@code alter table} — a non-null flag would need a default here and a matching
     * {@code @ColumnInfo(defaultValue = ...)} on the entity, or Room rejects the migrated table.
     */
    static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("alter table card add column pendingSince INTEGER");
        }
    };

    /**
     * Adds the streak snapshot and the local review tally.
     *
     * <p>The statements are copied from the schema Room generated rather than written by hand:
     * Room compares the migrated tables against its own expectation column by column, and a
     * hand-written {@code create table} that differs in any of them fails at open time on a real
     * device rather than here.
     */
    static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `stats_snapshot` (`id` INTEGER NOT NULL,"
                    + " `currentStreakDays` INTEGER NOT NULL, `fetchedAt` INTEGER,"
                    + " PRIMARY KEY(`id`))");
            db.execSQL("CREATE TABLE IF NOT EXISTS `review_tally` (`day` INTEGER NOT NULL,"
                    + " `reviews` INTEGER NOT NULL, PRIMARY KEY(`day`))");
        }
    };

    /**
     * Adds the table generated candidates wait in.
     *
     * <p>Statement copied from the schema Room generated rather than written by hand, like the one
     * above: Room compares the migrated table against its own expectation column by column, and a
     * hand-written {@code create table} differing in any of them fails at open time on a real
     * device rather than here.
     */
    static final Migration MIGRATION_5_6 = new Migration(5, 6) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `candidate` (`id` INTEGER PRIMARY KEY"
                    + " AUTOINCREMENT NOT NULL, `topicId` INTEGER NOT NULL,"
                    + " `front` TEXT NOT NULL, `back` TEXT NOT NULL,"
                    + " `generatedAt` INTEGER)");
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
                            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                                    MIGRATION_4_5, MIGRATION_5_6)
                            .build();
                }
            }
        }
        return instance;
    }
}
