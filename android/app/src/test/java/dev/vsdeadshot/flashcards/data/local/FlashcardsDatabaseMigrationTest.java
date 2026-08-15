package dev.vsdeadshot.flashcards.data.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.app.Application;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import androidx.room.Room;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Every migration, run against a real version 1 file.
 *
 * <p>This one is worth its length. The migration adds the local id scheme, and the way to get
 * that wrong is to rebuild {@code card} — which assigns new row ids, while every row in
 * {@code pending_review} points at an old one. A queued review that no longer resolves is the
 * loss this whole design exists to prevent, and it would be silent: the outbox would still be
 * full, the reviews would still be sent, and the server would answer 404 for cards that exist.
 *
 * <p>The version 1 schema is written out by hand rather than read from {@code schemas/1.json}.
 * It is frozen — version 1 can never change again — so there is nothing for the copy to drift
 * against, and this keeps the test free of {@code room-testing} and the asset plumbing it needs
 * to find that file from a unit test.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class FlashcardsDatabaseMigrationTest {

    private static final String NAME = "migration-test.db";

    /**
     * What Room stamps into a version 1 database. Without it Room cannot tell that the file it
     * opened is a schema it knows, and refuses before running any migration at all.
     */
    private static final String V1_IDENTITY_HASH = "77bf4ae88034f85f1c570961ec39fc53";

    private Context context;
    private FlashcardsDatabase db;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(NAME);
    }

    @After
    public void tearDown() {
        if (db != null) {
            db.close();
        }
        context.deleteDatabase(NAME);
    }

    @Test
    public void aQueuedReviewStillFindsItsCardAfterTheMigration() {
        writeVersion1Database();

        db = openAtCurrentVersion();

        PendingReviewEntity queued = db.pendingReviews().queued().get(0);
        assertEquals("the outbox row is untouched", 7L, queued.cardId);
        assertNotNull("and still resolves to its card, which is the whole point",
                db.cards().findById(queued.cardId));
    }

    @Test
    public void everyMigratedCardKeepsItsIdAndGainsItAsAServerId() {
        writeVersion1Database();

        db = openAtCurrentVersion();

        CardEntity card = db.cards().findById(7L);
        assertNotNull("the row survives with the id it had", card);
        assertEquals("which in a version 1 database was the server's own id",
                Long.valueOf(7L), card.serverId);
        assertNull("nothing was written here, so there is no create key", card.clientCardId);
        assertNull("and nothing has been refused, the card having never been offered",
                card.syncError);
        assertNull("nor does it differ from the server's copy", card.pendingSince);
        assertEquals("and the rest of the row came across intact", "What is a deadlock?",
                card.front);
        assertEquals(6, card.intervalDays);
    }

    /**
     * A migrated card has a {@code serverId}, so it is still a cache — the pull may drop it.
     * Only a card written on this device after the migration is exempt, and nothing in a
     * version 1 database can be one.
     */
    @Test
    public void aMigratedCardIsStillACacheTheServerCanDrop() {
        writeVersion1Database();

        db = openAtCurrentVersion();
        db.cards().deleteMissing(List.of(99L));

        assertNull("the server no longer lists it, so it goes", db.cards().findById(7L));
    }

    /** Every migration, in order — Room runs the whole chain from whatever version it finds. */
    private FlashcardsDatabase openAtCurrentVersion() {
        return Room.databaseBuilder(context, FlashcardsDatabase.class, NAME)
                .addMigrations(FlashcardsDatabase.MIGRATION_1_2, FlashcardsDatabase.MIGRATION_2_3,
                        FlashcardsDatabase.MIGRATION_3_4,
                        FlashcardsDatabase.MIGRATION_4_5)
                .allowMainThreadQueries()
                .build();
    }

    /** The schema exactly as {@code schemas/1.json} records it, with a row in every table. */
    private void writeVersion1Database() {
        SQLiteDatabase v1 = context.openOrCreateDatabase(NAME, Context.MODE_PRIVATE, null);
        try {
            v1.execSQL("CREATE TABLE IF NOT EXISTS `topic` (`id` INTEGER NOT NULL, "
                    + "`name` TEXT NOT NULL, `slug` TEXT NOT NULL, `createdAt` INTEGER, "
                    + "PRIMARY KEY(`id`))");
            v1.execSQL("CREATE TABLE IF NOT EXISTS `card` (`id` INTEGER NOT NULL, "
                    + "`topicId` INTEGER NOT NULL, `front` TEXT NOT NULL, `back` TEXT NOT NULL, "
                    + "`easeFactor` REAL NOT NULL, `intervalDays` INTEGER NOT NULL, "
                    + "`repetitions` INTEGER NOT NULL, `lapses` INTEGER NOT NULL, "
                    + "`dueDate` INTEGER, `lastReviewedAt` INTEGER, `archived` INTEGER NOT NULL, "
                    + "PRIMARY KEY(`id`))");
            v1.execSQL("CREATE INDEX IF NOT EXISTS `index_card_archived_dueDate_id` "
                    + "ON `card` (`archived`, `dueDate`, `id`)");
            v1.execSQL("CREATE TABLE IF NOT EXISTS `pending_review` "
                    + "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "`cardId` INTEGER NOT NULL, `confidence` INTEGER NOT NULL, "
                    + "`reviewedAt` INTEGER, `clientReviewId` TEXT NOT NULL, "
                    + "`attempts` INTEGER NOT NULL, `lastError` TEXT)");
            v1.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS "
                    + "`index_pending_review_clientReviewId` ON `pending_review` "
                    + "(`clientReviewId`)");

            v1.execSQL("CREATE TABLE IF NOT EXISTS room_master_table "
                    + "(id INTEGER PRIMARY KEY, identity_hash TEXT)");
            v1.execSQL("INSERT OR REPLACE INTO room_master_table (id, identity_hash) "
                    + "VALUES(42, '" + V1_IDENTITY_HASH + "')");

            v1.execSQL("INSERT INTO topic (id, name, slug, createdAt) "
                    + "VALUES (1, 'Operating Systems', 'operating-systems', 1767225600000)");
            v1.execSQL("INSERT INTO card (id, topicId, front, back, easeFactor, intervalDays, "
                    + "repetitions, lapses, dueDate, lastReviewedAt, archived) "
                    + "VALUES (7, 1, 'What is a deadlock?', 'Four Coffman conditions', "
                    + "2.5, 6, 2, 0, 20700, 1767225600000, 0)");
            v1.execSQL("INSERT INTO pending_review (cardId, confidence, reviewedAt, "
                    + "clientReviewId, attempts, lastError) VALUES "
                    + "(7, 4, 1767225600000, '3f2504e0-4f89-11d3-9a0c-0305e82c3301', 0, NULL)");

            v1.setVersion(1);
        } finally {
            v1.close();
        }
    }
}
