package dev.vsdeadshot.flashcards.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import androidx.room.Room;
import dev.vsdeadshot.flashcards.data.local.CardEntity;
import dev.vsdeadshot.flashcards.data.local.FlashcardsDatabase;
import dev.vsdeadshot.flashcards.data.local.TopicEntity;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Writing a card with no network.
 *
 * <p>What these are really about is the local id: a card written here has to be a first-class
 * row in the same table as everything else — studiable, editable, findable — while having no
 * server id at all, and it has to survive the sync that runs before it has one.
 */
@RunWith(RobolectricTestRunner.class)
// FlashcardsApp is kept out of this: Robolectric does not create the app's content
// providers, so androidx.startup never initialises WorkManager and onCreate's call to it
// throws. A data-layer test has no business running the app's startup wiring anyway.
@Config(application = Application.class)
public class CardRepositoryTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 17);

    private FlashcardsDatabase db;
    private CardRepository repository;

    @Before
    public void setUp() {
        db = Room.inMemoryDatabaseBuilder(
                        RuntimeEnvironment.getApplication(), FlashcardsDatabase.class)
                .allowMainThreadQueries()
                .build();
        repository = new CardRepository(
                db, Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC));
        cacheTopic(1L);
    }

    @After
    public void tearDown() {
        db.close();
    }

    @Test
    public void aCardWrittenHereHasNoServerIdAndAKeyToClaimOneWith() {
        CardEntity written = repository.create(1L, "What is a deadlock?", "Four conditions");

        assertNull("nothing has created it on the server yet", written.serverId);
        assertNotNull("and the key that will is minted now, not at send time",
                written.clientCardId);
        assertTrue("a local id is below every server id, so a later pull cannot collide with it",
                written.id < 0);
    }

    @Test
    public void itIsAnOrdinaryRowInTheSameTableEverythingElseReads() {
        CardEntity written = repository.create(1L, "What is a deadlock?", "Four conditions");

        CardEntity read = db.cards().findById(written.id);
        assertNotNull("findable by its local id like any other card", read);
        assertEquals("What is a deadlock?", read.front);
        assertEquals(1L, read.topicId);
        assertEquals("and the key survived the round trip through the converter",
                written.clientCardId, read.clientCardId);
    }

    @Test
    public void itIsDueTodaySoItCanBeStudiedInTheSessionItWasWrittenIn() {
        repository.create(1L, "front", "back");

        List<CardEntity> queue = db.cards().queue(TODAY, 10);

        assertEquals("the new card is in today's queue", 1, queue.size());
        assertEquals(TODAY, queue.get(0).dueDate);
    }

    @Test
    public void itStartsOnTheScheduleTheServerWouldHaveGivenIt() {
        CardEntity written = repository.create(1L, "front", "back");

        assertEquals("the initial ease the scheduler defines", 2.5d, written.easeFactor, 1e-9);
        assertEquals("never reviewed, so no interval", 0, written.intervalDays);
        assertEquals(0, written.repetitions);
        assertEquals(0, written.lapses);
        assertNull("and never reviewed", written.lastReviewedAt);
    }

    @Test
    public void everyCardGetsItsOwnIdAndItsOwnKey() {
        CardEntity first = repository.create(1L, "first", "back");
        CardEntity second = repository.create(1L, "second", "back");

        assertNotEquals("two creates cannot share a local id", first.id, second.id);
        assertNotEquals("nor a key, or the server would treat the second as a retry of the first",
                first.clientCardId, second.clientCardId);
        assertEquals("and both are stored", 2, db.cards().findAllActive().size());
    }

    /**
     * The reason local ids run downwards. A card pulled from the server is stored under the
     * server's id, so a local id taken from the top of the table would eventually be handed to
     * a card the server later creates under the same number.
     */
    @Test
    public void aLocalIdCannotCollideWithACardTheServerSends() {
        CardEntity pulled = new CardEntity();
        pulled.id = 1L;
        pulled.serverId = 1L;
        pulled.topicId = 1L;
        pulled.front = "from the server";
        pulled.back = "back";
        pulled.dueDate = TODAY;
        db.cards().upsertAll(List.of(pulled));

        CardEntity written = repository.create(1L, "written here", "back");

        assertTrue("a local id is on the other side of zero from every server id", written.id < 0);
        assertNotNull("and the pulled card is untouched", db.cards().findById(1L));
    }

    @Test
    public void aSyncThatFindsNothingOnTheServerLeavesAnUnsyncedCardAlone() {
        CardEntity written = repository.create(1L, "written here", "back");

        // What a pull does when the server lists no cards at all.
        db.cards().deleteAllFromServer();

        assertNotNull("this row is the only copy of it; a pull may not throw it away",
                db.cards().findById(written.id));
    }

    @Test
    public void aSyncThatListsOtherCardsLeavesAnUnsyncedCardAlone() {
        CardEntity written = repository.create(1L, "written here", "back");

        // The server listed a card this device has never seen, and not the one just written —
        // which it could not have, never having been told about it.
        db.cards().deleteMissing(List.of(99L));

        assertNotNull("not listed is not the same as deleted, for a card never offered",
                db.cards().findById(written.id));
    }

    @Test
    public void editingChangesTheTextAndLeavesTheScheduleAlone() {
        CardEntity written = repository.create(1L, "typo", "back");
        cacheTopic(2L);

        CardEntity edited = repository.edit(written.id, 2L, "fixed", "back");

        assertEquals("fixed", edited.front);
        assertEquals("and it moved topic", 2L, edited.topicId);
        assertEquals("but a typo fix does not reset progress", TODAY, edited.dueDate);
        assertEquals(2.5d, edited.easeFactor, 1e-9);
        assertEquals("the key it will be created under is unchanged",
                written.clientCardId, db.cards().findById(written.id).clientCardId);
    }

    /**
     * Nothing sends an edit to the server yet, so the next pull would overwrite the row with the
     * server's copy and the change would vanish. Refusing says that; writing it would not.
     */
    @Test
    public void aCardTheServerAlreadyHasCannotBeEditedYet() {
        CardEntity pulled = new CardEntity();
        pulled.id = 1L;
        pulled.serverId = 1L;
        pulled.topicId = 1L;
        pulled.front = "from the server";
        pulled.back = "back";
        pulled.dueDate = TODAY;
        db.cards().upsertAll(List.of(pulled));

        assertThrows(IllegalArgumentException.class,
                () -> repository.edit(1L, 1L, "changed", "back"));

        assertEquals("and the row is left as the server has it",
                "from the server", db.cards().findById(1L).front);
    }

    @Test
    public void aCardThatIsNotCachedCannotBeEdited() {
        assertThrows(IllegalArgumentException.class,
                () -> repository.edit(-99L, 1L, "front", "back"));
    }

    @Test
    public void aTopicThisDeviceDoesNotKnowAboutIsRefusedAndNothingIsWritten() {
        assertThrows(IllegalArgumentException.class,
                () -> repository.create(404L, "front", "back"));

        assertTrue("no half-written card is left behind", db.cards().findAllActive().isEmpty());
    }

    @Test
    public void aBlankSideIsRefusedBeforeAnythingIsWritten() {
        assertThrows(IllegalArgumentException.class, () -> repository.create(1L, "  ", "back"));
        assertThrows(IllegalArgumentException.class, () -> repository.create(1L, "front", null));

        assertTrue(db.cards().findAllActive().isEmpty());
    }

    private void cacheTopic(long id) {
        TopicEntity topic = new TopicEntity();
        topic.id = id;
        topic.name = "Operating Systems " + id;
        topic.slug = "operating-systems-" + id;
        topic.createdAt = Instant.parse("2026-01-01T00:00:00Z");
        db.topics().upsertAll(List.of(topic));
    }
}
