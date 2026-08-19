package dev.vsdeadshot.flashcards.ui.cards;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.os.Looper;
import androidx.room.Room;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.testing.WorkManagerTestInitHelper;
import dev.vsdeadshot.flashcards.data.CandidateRepository;
import dev.vsdeadshot.flashcards.data.CardRepository;
import dev.vsdeadshot.flashcards.data.local.CardEntity;
import dev.vsdeadshot.flashcards.data.local.FlashcardsDatabase;
import dev.vsdeadshot.flashcards.data.local.TopicEntity;
import dev.vsdeadshot.flashcards.data.sync.SyncScheduler;
import dev.vsdeadshot.flashcards.ui.cards.CardEditorViewModel.EditorState;
import dev.vsdeadshot.flashcards.ui.cards.CardEditorViewModel.Outcome;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Writing one card.
 *
 * <p>Three things here are worth more than the create and edit themselves: that a device with no
 * topics says so instead of accepting a card nothing will take, that a card which vanished under
 * the editor is reported rather than thrown, and that the outcome is consumed — a save navigates,
 * and a value redelivered after a rotation would navigate again from a screen that had left.
 */
@RunWith(RobolectricTestRunner.class)
// FlashcardsApp is kept out of this: Robolectric does not create the app's content providers, so
// androidx.startup never initialises WorkManager and onCreate's call to it throws. The test
// WorkManager set up below is what saving talks to instead.
@Config(application = Application.class)
public class CardEditorViewModelTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 17);
    private static final Clock FIXED =
            Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
    private static final Executor DIRECT = Runnable::run;

    private FlashcardsDatabase db;
    private CardRepository repository;
    private CardEditorViewModel model;

    @Before
    public void setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(RuntimeEnvironment.getApplication());
        db = Room.inMemoryDatabaseBuilder(
                        RuntimeEnvironment.getApplication(), FlashcardsDatabase.class)
                .allowMainThreadQueries()
                .build();
        repository = new CardRepository(db, FIXED);
        cacheTopic(1L, "Operating Systems");
    }

    @After
    public void tearDown() {
        db.close();
    }

    // ---- opening --------------------------------------------------------------------------

    @Test
    public void anEditorForANewCardHasNoCardAndEveryTopic() {
        cacheTopic(2L, "Databases");

        open(CardEditorViewModel.NEW_CARD);

        assertTrue(state().isNew());
        assertNull(state().card());
        assertEquals(2, state().topics().size());
        assertTrue(state().canSave());
    }

    @Test
    public void anEditorForAnExistingCardIsLoadedWithIt() {
        CardEntity written = repository.create(1L, "What is a deadlock?", "Four conditions");

        open(written.id);

        assertFalse(state().isNew());
        assertEquals("What is a deadlock?", state().card().front);
    }

    /**
     * A card archived on another device between the list being drawn and a row being tapped. The
     * editor is told, so it can say so and leave.
     */
    @Test
    public void aCardThatIsNoLongerThereIsReportedAsMissing() {
        open(404L);

        assertTrue(state().missing());
        assertFalse("nothing typed into it could be saved", state().canSave());
    }

    /**
     * Every save would be refused, because create and edit both reject a topic this device does
     * not know about. Offering the fields anyway would let somebody write a card and lose it.
     */
    @Test
    public void aDeviceWithNoTopicsCannotSaveAtAll() {
        db.topics().deleteAll();

        open(CardEditorViewModel.NEW_CARD);

        assertTrue(state().topics().isEmpty());
        assertFalse(state().canSave());
    }

    // ---- saving ---------------------------------------------------------------------------

    @Test
    public void savingANewCardWritesItAndAsksForASync() throws Exception {
        open(CardEditorViewModel.NEW_CARD);

        model.save(1L, "What is a page fault?", "A trap");
        idle();

        assertEquals(Outcome.Kind.SAVED, outcome().kind());
        assertEquals(1, repository.list().size());
        assertEquals("What is a page fault?", repository.list().get(0).front);
        assertEquals("a card nobody has been told about is what the outbox exists to stop being"
                + " permanent", 1, immediateSyncRequests());
    }

    @Test
    public void savingAnEditReplacesTheTextAndKeepsTheSchedule() {
        CardEntity written = repository.create(1L, "typo", "back");
        int intervalBefore = written.intervalDays;
        open(written.id);

        model.save(1L, "corrected", "back");
        idle();

        assertEquals(Outcome.Kind.SAVED, outcome().kind());
        CardEntity after = repository.find(written.id);
        assertEquals("corrected", after.front);
        assertEquals("correcting a typo must not reset a card's progress",
                intervalBefore, after.intervalDays);
    }

    /**
     * The repository refuses a blank side whichever screen is asking. The editor checks too, so
     * the message lands beside the field — but this is the check that makes it a rule.
     */
    @Test
    public void aBlankSideIsRefusedAndSaysWhy() throws Exception {
        open(CardEditorViewModel.NEW_CARD);

        model.save(1L, "   ", "back");
        idle();

        assertEquals(Outcome.Kind.REFUSED, outcome().kind());
        assertNotNull("a refusal with nothing to show would leave the screen silent",
                outcome().message());
        assertTrue(repository.list().isEmpty());
        assertEquals("nothing was written, so there is nothing to send", 0, immediateSyncRequests());
    }

    // ---- archiving ------------------------------------------------------------------------

    @Test
    public void archivingACardTheServerHasMarksItRatherThanRemovingIt() {
        CardEntity pulled = cachePulledCard(7L);
        open(pulled.id);

        model.archive();
        idle();

        assertEquals(Outcome.Kind.ARCHIVED, outcome().kind());
        assertTrue("it is out of circulation", repository.list().isEmpty());
        assertNotNull("but the row is still here, waiting to tell the server",
                repository.find(7L));
    }

    /**
     * The asymmetry the confirmation dialog exists to warn about: this row is the only copy that
     * exists anywhere, so archiving it is a delete with nothing to undo it.
     */
    @Test
    public void archivingACardTheServerNeverSawRemovesItOutright() {
        CardEntity written = repository.create(1L, "written here", "back");
        open(written.id);

        model.archive();
        idle();

        assertEquals(Outcome.Kind.ARCHIVED, outcome().kind());
        assertNull("there was nothing to tell the server, so there is nothing to keep",
                repository.find(written.id));
    }

    // ---- the outcome is consumed ----------------------------------------------------------

    /**
     * Acting on an outcome navigates. A plain {@code LiveData} redelivers its last value to a new
     * observer, so a rotation after saving would navigate a second time from a screen that had
     * already left.
     */
    @Test
    public void anOutcomeIsGoneOnceItHasBeenActedOn() {
        open(CardEditorViewModel.NEW_CARD);
        model.save(1L, "front", "back");
        idle();
        assertNotNull(outcome());

        model.consumeOutcome();

        assertNull("a screen that arrives after this must not be told to leave again", outcome());
    }

    // ---- fixtures ---------------------------------------------------------------------------

    private void open(long cardId) {
        model = new CardEditorViewModel(RuntimeEnvironment.getApplication(), repository,
                new CandidateRepository(db), DIRECT, cardId, CardEditorViewModel.NO_CANDIDATE);
        idle();
    }

    private EditorState state() {
        idle();
        return model.state().getValue();
    }

    private Outcome outcome() {
        idle();
        return model.outcome().getValue();
    }

    private void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private int immediateSyncRequests() throws Exception {
        List<WorkInfo> enqueued = WorkManager.getInstance(RuntimeEnvironment.getApplication())
                .getWorkInfosForUniqueWork(SyncScheduler.IMMEDIATE_WORK)
                .get();
        return enqueued.size();
    }

    private CardEntity cachePulledCard(long id) {
        CardEntity pulled = new CardEntity();
        pulled.id = id;
        pulled.serverId = id;
        pulled.topicId = 1L;
        pulled.front = "from the server";
        pulled.back = "back";
        pulled.dueDate = TODAY;
        db.cards().upsertAll(List.of(pulled));
        return pulled;
    }

    private void cacheTopic(long id, String name) {
        TopicEntity topic = new TopicEntity();
        topic.id = id;
        topic.name = name;
        topic.slug = name.toLowerCase().replace(' ', '-');
        topic.createdAt = Instant.parse("2026-01-01T00:00:00Z");
        db.topics().upsertAll(List.of(topic));
    }
}
