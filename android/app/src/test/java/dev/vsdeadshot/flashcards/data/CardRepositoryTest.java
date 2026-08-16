package dev.vsdeadshot.flashcards.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import androidx.room.Room;
import dev.vsdeadshot.flashcards.data.local.CardEntity;
import dev.vsdeadshot.flashcards.data.local.CardSummaryRow;
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
 * Writing a card with no network, and reading back the cards there are.
 *
 * <p>What the writing tests are really about is the local id: a card written here has to be a
 * first-class row in the same table as everything else — studiable, editable, findable — while
 * having no server id at all, and it has to survive the sync that runs before it has one.
 *
 * <p>The listing tests are about what a person can be told. A card is shown whether or not its
 * topic is cached, and a row says at most one thing about syncing: that the server refused it, or
 * failing that, that the server has not caught up.
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
        cachePulledCard(1L);

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

    @Test
    public void editingACardTheServerHasMarksItAsNeedingToBeSent() {
        cachePulledCard(1L);

        repository.edit(1L, 1L, "changed", "back");

        CardEntity edited = db.cards().findById(1L);
        assertEquals("changed", edited.front);
        assertNotNull("the row now differs from the server's copy, and says so",
                edited.pendingSince);
    }

    /**
     * Marked whether or not the server has the card. For one still waiting to be created the
     * marker is redundant — the create carries the new text anyway — but being unconditional is
     * what makes an edit landing mid-create safe, since the create only takes the marker down if
     * the row still holds what it sent.
     */
    @Test
    public void editingACardTheServerHasNeverSeenMarksItTheSameWay() {
        CardEntity written = repository.create(1L, "typo", "back");

        repository.edit(written.id, 1L, "fixed", "back");

        assertNotNull("marked the same way regardless",
                db.cards().findById(written.id).pendingSince);
    }

    @Test
    public void archivingACardTheServerHasMarksItRatherThanRemovingIt() {
        cachePulledCard(1L);

        repository.archive(1L);

        CardEntity archived = db.cards().findById(1L);
        assertNotNull("the row stays — the server archives too, so history survives", archived);
        assertTrue(archived.archived);
        assertNotNull("and the delete still has to be sent", archived.pendingSince);
    }

    @Test
    public void aCardThatIsNotCachedCannotBeArchived() {
        assertThrows(IllegalArgumentException.class, () -> repository.archive(-99L));
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

    // ---- listing ------------------------------------------------------------------------------

    @Test
    public void theListingCarriesEachCardsTopicName() {
        cachePulledCard(1L);

        List<CardSummaryRow> listed = repository.list();

        assertEquals(1, listed.size());
        assertEquals("from the server", listed.get(0).front);
        assertEquals("Operating Systems 1", listed.get(0).topicName);
    }

    /**
     * The reason the join is a left one. A pull can write a card ahead of its topic, and a topic
     * can be deleted on the server; a card disappearing from the one screen that shows all of them
     * would be the worse answer by far.
     */
    @Test
    public void aCardWhoseTopicIsNotCachedIsStillListed() {
        CardEntity orphan = new CardEntity();
        orphan.id = 5L;
        orphan.serverId = 5L;
        orphan.topicId = 404L;
        orphan.front = "topic went away";
        orphan.back = "back";
        orphan.dueDate = TODAY;
        db.cards().upsertAll(List.of(orphan));

        List<CardSummaryRow> listed = repository.list();

        assertEquals(1, listed.size());
        assertNull("there is nothing to call it, which is not the same as nothing to show",
                listed.get(0).topicName);
    }

    @Test
    public void anArchivedCardIsOutOfCirculationAndOutOfTheListing() {
        cachePulledCard(1L);
        repository.archive(1L);

        assertTrue(repository.list().isEmpty());
    }

    /**
     * Ordered by topic, then by id ascending. Local ids run downwards from zero, so the card
     * written most recently is the one nearest the top of its topic.
     */
    @Test
    public void cardsAreGroupedByTopicAndTheNewestLocalOneComesFirst() {
        cacheTopic(2L);
        cachePulledCard(1L);
        CardEntity first = repository.create(1L, "written first", "back");
        CardEntity second = repository.create(1L, "written second", "back");
        CardEntity elsewhere = repository.create(2L, "another topic", "back");

        List<CardSummaryRow> listed = repository.list();

        assertEquals(4, listed.size());
        assertEquals(second.id, listed.get(0).id);
        assertEquals(first.id, listed.get(1).id);
        assertEquals("the pulled card is filed under the same topic, below both local ones",
                1L, listed.get(2).id);
        assertEquals("Operating Systems 2 sorts after Operating Systems 1",
                elsewhere.id, listed.get(3).id);
    }

    // ---- what a row says about syncing --------------------------------------------------------

    @Test
    public void aCardTheServerHasAndAgreesWithSaysNothing() {
        cachePulledCard(1L);

        CardSummaryRow row = repository.list().get(0);

        assertFalse(row.unsent());
        assertFalse(row.rejected());
    }

    @Test
    public void aCardWrittenHereIsUnsentUntilTheServerHasIt() {
        repository.create(1L, "written here", "back");

        CardSummaryRow row = repository.list().get(0);

        assertTrue(row.unsent());
        assertFalse("nothing has refused it; it has simply not been offered yet", row.rejected());
    }

    @Test
    public void aCardEditedSinceItLastSyncedIsUnsentTheSameWay() {
        cachePulledCard(1L);
        repository.edit(1L, 1L, "corrected", "back");

        CardSummaryRow row = repository.list().get(0);

        assertTrue("which of the two reasons it is makes no difference to somebody reading a list",
                row.unsent());
    }

    /**
     * The one state worth acting on, and the reason it is checked first: a refused card is also
     * unsent, and saying only that would hide the fact that nothing will change until it is
     * edited.
     */
    @Test
    public void aCardTheServerRefusedIsRejectedAsWellAsUnsent() {
        CardEntity written = repository.create(1L, "written here", "back");
        db.cards().recordSyncFailure(written.id, "No topic with id 42");

        CardSummaryRow row = repository.list().get(0);

        assertTrue(row.rejected());
        assertEquals("the server's own words, which are the only actionable part",
                "No topic with id 42", row.syncError);
        assertTrue(row.unsent());
    }

    @Test
    public void editingARefusedCardClearsWhatWasSaidAboutIt() {
        CardEntity written = repository.create(1L, "written here", "back");
        db.cards().recordSyncFailure(written.id, "No topic with id 42");

        repository.edit(written.id, 1L, "written here", "corrected");

        assertFalse("editing is the recovery path, so it has to undo the parking",
                repository.list().get(0).rejected());
    }

    // ---- the editor's reads -------------------------------------------------------------------

    @Test
    public void aCardCanBeFetchedWholeForEditing() {
        CardEntity written = repository.create(1L, "front", "back");

        CardEntity found = repository.find(written.id);

        assertNotNull(found);
        assertEquals("back", found.back);
        assertEquals(1L, found.topicId);
    }

    /**
     * A sync can archive a card between a list being drawn and a row being tapped. An editor that
     * is told so can say so; one that is thrown at cannot.
     */
    @Test
    public void aCardThatIsGoneIsFoundAsNothingRatherThanThrown() {
        assertNull(repository.find(404L));
    }

    @Test
    public void theTopicsOfferedAreTheOnesWritingWouldAccept() {
        cacheTopic(2L);

        assertEquals(2, repository.topics().size());
    }

    /**
     * A device that has never synced has no topics, and {@code create} refuses every one of them.
     * An editor showing an empty picker would let somebody type out a card and then fail on save.
     */
    @Test
    public void aDeviceThatHasNeverSyncedIsOfferedNoTopicsAtAll() {
        db.topics().deleteAll();

        assertTrue(repository.topics().isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> repository.create(1L, "front", "back"));
    }

    /** A card as a pull would have left it: the server's id in both places, nothing unsent. */
    private void cachePulledCard(long id) {
        CardEntity pulled = new CardEntity();
        pulled.id = id;
        pulled.serverId = id;
        pulled.topicId = 1L;
        pulled.front = "from the server";
        pulled.back = "back";
        pulled.dueDate = TODAY;
        db.cards().upsertAll(List.of(pulled));
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
