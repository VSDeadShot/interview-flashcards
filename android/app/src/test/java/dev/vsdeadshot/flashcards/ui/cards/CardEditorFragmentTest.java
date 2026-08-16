package dev.vsdeadshot.flashcards.ui.cards;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.Dialog;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.RecyclerView;
import androidx.room.Room;
import androidx.work.testing.WorkManagerTestInitHelper;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import dev.vsdeadshot.flashcards.R;
import dev.vsdeadshot.flashcards.data.CardRepository;
import dev.vsdeadshot.flashcards.data.local.CardEntity;
import dev.vsdeadshot.flashcards.data.local.FlashcardsDatabase;
import dev.vsdeadshot.flashcards.data.local.TopicEntity;
import dev.vsdeadshot.flashcards.ui.Graph;
import dev.vsdeadshot.flashcards.ui.MainActivity;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowToast;

/**
 * The editor as it is actually reached: a row tapped in the list, the fields filled from the card,
 * and the way back.
 *
 * <p>The part nothing else covers is the rejection banner. It is the whole reason
 * {@code card.syncError} is stored rather than discarded, and this is the only place a person ever
 * sees it.
 */
@RunWith(RobolectricTestRunner.class)
// FlashcardsApp is kept out of this: Robolectric does not create the app's content providers, so
// androidx.startup never initialises WorkManager and onCreate's call to it throws.
@Config(application = Application.class)
public class CardEditorFragmentTest {

    private FlashcardsDatabase db;
    private CardRepository repository;
    private MainActivity activity;

    @Before
    public void setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(RuntimeEnvironment.getApplication());
        db = Room.inMemoryDatabaseBuilder(
                        RuntimeEnvironment.getApplication(), FlashcardsDatabase.class)
                .allowMainThreadQueries()
                .build();
        Graph.installDatabase(db);
        repository = new CardRepository(db);
        cacheTopic();
    }

    @After
    public void tearDown() {
        Graph.reset();
        db.close();
    }

    @Test
    public void tappingARowOpensTheEditorLoadedWithThatCard() throws Exception {
        repository.create(1L, "What is a deadlock?", "Four conditions");

        openEditorForFirstRow();

        assertEquals(R.id.cardEditorFragment, currentDestination());
        assertEquals("What is a deadlock?", text(R.id.editor_front));
        assertEquals("Four conditions", text(R.id.editor_back));
        assertEquals("Operating Systems", text(R.id.editor_topic));
    }

    @Test
    public void theButtonOpensAnEmptyEditorTitledForANewCard() throws Exception {
        openList();

        activity.findViewById(R.id.cards_new).performClick();
        settle();

        assertEquals(R.id.cardEditorFragment, currentDestination());
        assertEquals("", text(R.id.editor_front));
        assertEquals("a new card has nothing to archive", View.GONE,
                activity.findViewById(R.id.editor_archive).getVisibility());
        assertEquals("New card", activity.getSupportActionBar().getTitle().toString());
    }

    /**
     * The whole point of keeping {@code syncError} rather than discarding it: the list says which
     * card needs attention, and this says why, in the server's own words, on the screen where
     * editing clears it and offers the card again.
     */
    @Test
    public void aRefusedCardExplainsItselfInTheServersOwnWords() throws Exception {
        long cardId = repository.create(1L, "written here", "back").id;
        db.cards().recordSyncFailure(cardId, "No topic with id 42");

        openEditorForFirstRow();

        assertEquals(View.VISIBLE,
                activity.findViewById(R.id.editor_rejected_banner).getVisibility());
        assertTrue("a generic message would drop the only actionable part",
                text(R.id.editor_rejected_text).contains("No topic with id 42"));
    }

    @Test
    public void aCardWithNothingWrongWithItShowsNoBanner() throws Exception {
        cachePulledCard(3L);

        openEditorForFirstRow();

        assertEquals(View.GONE,
                activity.findViewById(R.id.editor_rejected_banner).getVisibility());
    }

    @Test
    public void savingAnEditWritesItAndGoesBackToTheList() throws Exception {
        long cardId = repository.create(1L, "typo", "back").id;
        openEditorForFirstRow();

        setText(R.id.editor_front, "corrected");
        activity.findViewById(R.id.editor_save).performClick();
        settle();

        assertEquals("corrected", repository.find(cardId).front);
        assertEquals("saving is done, so the editor has no reason to stay",
                R.id.cardListFragment, currentDestination());
    }

    @Test
    public void aBlankFieldIsMarkedAndNothingIsSaved() throws Exception {
        long cardId = repository.create(1L, "front", "back").id;
        openEditorForFirstRow();

        setText(R.id.editor_front, "   ");
        activity.findViewById(R.id.editor_save).performClick();
        settle();

        TextInputLayout frontLayout = activity.findViewById(R.id.editor_front_layout);
        assertNotNull("the message belongs beside the field somebody has to fix",
                frontLayout.getError());
        assertEquals("front", repository.find(cardId).front);
        assertEquals("nothing was saved, so the editor stays open",
                R.id.cardEditorFragment, currentDestination());
        // The repository refuses a blank side too, so without the editor's own guard the save
        // would still be rejected — but its message would arrive as a toast written for a caller,
        // on top of the error already sitting beside the field. This is what pins the guard.
        assertNull("the field says it; the repository must not say it again",
                ShadowToast.getTextOfLatestToast());
    }

    /**
     * Archiving is two different things depending on whether the server has the card, and the
     * dialog is where that asymmetry is said out loud.
     */
    @Test
    public void archivingACardWrittenHereWarnsThatNothingCanBringItBack() throws Exception {
        repository.create(1L, "written here", "back");
        openEditorForFirstRow();

        activity.findViewById(R.id.editor_archive).performClick();
        settle();

        assertEquals(activity.getString(R.string.editor_archive_local), dialogMessage());
    }

    @Test
    public void archivingACardTheServerHasSaysItsHistorySurvives() throws Exception {
        cachePulledCard(3L);
        openEditorForFirstRow();

        activity.findViewById(R.id.editor_archive).performClick();
        settle();

        assertEquals(activity.getString(R.string.editor_archive_synced), dialogMessage());
    }

    @Test
    public void confirmingTheDialogArchivesAndGoesBack() throws Exception {
        cachePulledCard(3L);
        openEditorForFirstRow();
        activity.findViewById(R.id.editor_archive).performClick();
        settle();

        ((AlertDialog) ShadowDialog.getLatestDialog())
                .getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        settle();

        assertTrue("it is out of circulation", repository.list().isEmpty());
        assertEquals(R.id.cardListFragment, currentDestination());
    }

    @Test
    public void aDeviceWithNoTopicsSaysSoInsteadOfOfferingFields() throws Exception {
        db.topics().deleteAll();
        openList();

        activity.findViewById(R.id.cards_new).performClick();
        settle();

        assertEquals(View.VISIBLE,
                activity.findViewById(R.id.editor_no_topics).getVisibility());
        assertEquals("every save would be refused, so there is nothing to offer",
                View.GONE, activity.findViewById(R.id.editor_fields).getVisibility());
    }

    // ---- fixtures -----------------------------------------------------------------------------

    private void openList() throws InterruptedException {
        activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        BottomNavigationView bottomNav = activity.findViewById(R.id.bottom_nav);
        bottomNav.setSelectedItemId(R.id.cardListFragment);
        settle();
    }

    private void openEditorForFirstRow() throws InterruptedException {
        openList();
        RecyclerView list = activity.findViewById(R.id.cards_list);
        list.measure(0, 0);
        list.layout(0, 0, 1000, 2000);
        list.getLayoutManager().findViewByPosition(0).performClick();
        settle();
    }

    private int currentDestination() {
        NavHostFragment host = (NavHostFragment)
                activity.getSupportFragmentManager().findFragmentById(R.id.nav_host);
        NavController nav = host.getNavController();
        return nav.getCurrentDestination().getId();
    }

    private String dialogMessage() {
        Dialog dialog = ShadowDialog.getLatestDialog();
        assertNotNull("nothing asked before archiving", dialog);
        return ((TextView) dialog.findViewById(android.R.id.message)).getText().toString();
    }

    private String text(int id) {
        return ((TextView) activity.findViewById(id)).getText().toString();
    }

    private void setText(int id, String value) {
        ((TextInputEditText) activity.findViewById(id)).setText(value);
    }

    /**
     * Waits for the read, then delivers it. {@code Graph.io()} is one thread handing out work in
     * order, so a task queued after the view model's read has to wait for it.
     */
    private void settle() throws InterruptedException {
        shadowOf(Looper.getMainLooper()).idle();
        CountDownLatch behindTheRead = new CountDownLatch(1);
        Graph.io().execute(behindTheRead::countDown);
        behindTheRead.await(10, TimeUnit.SECONDS);
        shadowOf(Looper.getMainLooper()).idle();
    }

    private void cachePulledCard(long id) {
        CardEntity pulled = new CardEntity();
        pulled.id = id;
        pulled.serverId = id;
        pulled.topicId = 1L;
        pulled.front = "from the server";
        pulled.back = "back";
        pulled.dueDate = LocalDate.now();
        db.cards().upsertAll(List.of(pulled));
    }

    private void cacheTopic() {
        TopicEntity topic = new TopicEntity();
        topic.id = 1L;
        topic.name = "Operating Systems";
        topic.slug = "operating-systems";
        topic.createdAt = Instant.parse("2026-01-01T00:00:00Z");
        db.topics().upsertAll(List.of(topic));
    }
}
