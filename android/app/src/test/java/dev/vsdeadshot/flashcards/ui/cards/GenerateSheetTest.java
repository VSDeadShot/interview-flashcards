package dev.vsdeadshot.flashcards.ui.cards;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.room.Room;
import androidx.work.testing.WorkManagerTestInitHelper;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import dev.vsdeadshot.flashcards.R;
import dev.vsdeadshot.flashcards.data.local.FlashcardsDatabase;
import dev.vsdeadshot.flashcards.data.local.TopicEntity;
import dev.vsdeadshot.flashcards.ui.Graph;
import dev.vsdeadshot.flashcards.ui.MainActivity;
import java.time.Instant;
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

/**
 * The sheet as it is actually reached — the real toolbar item on the real card list, not a
 * fragment launched in isolation.
 *
 * <p>That the action is on the toolbar at all is worth pinning here, because nothing else can:
 * {@code MainActivity} inflates its own menu, and a fragment's {@code MenuProvider} is only ever
 * dispatched if the activity lets the superclass have the menu too. Get that wrong and the entry
 * point simply is not there, with nothing failing to say so.
 *
 * <p>The other thing this holds is that a failure is drawn <em>inside</em> the sheet. Generating
 * is the one feature here that can fail for reasons the user can act on — try again, change the
 * focus, turn the radio on — and every one of those actions needs the inputs they typed to still
 * be on screen.
 */
@RunWith(RobolectricTestRunner.class)
// FlashcardsApp is kept out of this: Robolectric does not create the app's content providers, so
// androidx.startup never initialises WorkManager and onCreate's call to it throws.
@Config(application = Application.class)
public class GenerateSheetTest {

    private FlashcardsDatabase db;

    @Before
    public void setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(RuntimeEnvironment.getApplication());
        db = Room.inMemoryDatabaseBuilder(
                        RuntimeEnvironment.getApplication(), FlashcardsDatabase.class)
                .allowMainThreadQueries()
                .build();
        Graph.installDatabase(db);
        cacheTopic(1L, "Operating Systems");
    }

    @After
    public void tearDown() {
        Graph.reset();
        db.close();
    }

    @Test
    public void theToolbarActionOpensASheetOfferingATopicAFocusAndACount() throws Exception {
        GenerateSheet sheet = openSheet();
        View view = sheet.requireView();

        assertNotNull("a topic picker is required", view.findViewById(R.id.generate_topic));
        assertNotNull("a focus field is required", view.findViewById(R.id.generate_focus));
        assertNotNull("a count field is required", view.findViewById(R.id.generate_count));
    }

    /**
     * The topics come from the cache, like everything else on screen. Typing a topic name would
     * mean inventing one the deck does not have, and the server would answer 404 to a name it
     * never issued an id for.
     */
    @Test
    public void theTopicsOfferedAreTheOnesTheCacheHolds() throws Exception {
        GenerateSheet sheet = openSheet();

        MaterialAutoCompleteTextView topic = sheet.requireView().findViewById(R.id.generate_topic);
        assertEquals("the cached topic should be pre-selected, so one tap is enough",
                "Operating Systems", topic.getText().toString());
    }

    /** Eight is the backend's own default. Agreeing on it means the common case is one tap. */
    @Test
    public void theCountStartsAtEightSoTheCommonCaseIsOneTap() throws Exception {
        GenerateSheet sheet = openSheet();

        TextInputEditText count = sheet.requireView().findViewById(R.id.generate_count);
        assertEquals("8", count.getText().toString());
    }

    @Test
    public void anErrorIsShownInTheSheetSoTheInputsAreStillThereToRetryWith() throws Exception {
        GenerateSheet sheet = openSheet();

        sheet.showError(R.string.generate_error_busy);
        shadowOf(Looper.getMainLooper()).idle();

        View view = sheet.requireView();
        TextView error = view.findViewById(R.id.generate_error);
        assertEquals("the message should be on screen", View.VISIBLE, error.getVisibility());
        assertEquals("the message should say what happened",
                sheet.getString(R.string.generate_error_busy), error.getText().toString());
        assertEquals("the inputs must survive an error so a retry keeps them",
                View.VISIBLE, view.findViewById(R.id.generate_focus).getVisibility());
        assertEquals("and the button has to come back, or there is no retrying",
                View.VISIBLE, view.findViewById(R.id.generate_go).getVisibility());
    }

    // ---- fixtures -----------------------------------------------------------------------------

    private GenerateSheet openSheet() throws InterruptedException {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        BottomNavigationView bottomNav = activity.findViewById(R.id.bottom_nav);
        bottomNav.setSelectedItemId(R.id.cardListFragment);
        settle();

        Toolbar toolbar = activity.findViewById(R.id.toolbar);
        assertNotNull("the card list has to put the action on the toolbar",
                toolbar.getMenu().findItem(R.id.action_generate));
        toolbar.getMenu().performIdentifierAction(R.id.action_generate, 0);
        settle();

        Fragment host = activity.getSupportFragmentManager().findFragmentById(R.id.nav_host);
        Fragment list = host.getChildFragmentManager().getFragments().get(0);
        GenerateSheet sheet = (GenerateSheet)
                list.getParentFragmentManager().findFragmentByTag(GenerateSheet.TAG);
        assertNotNull("tapping the action should have shown the sheet", sheet);
        return sheet;
    }

    /**
     * Waits for the read, then delivers it. {@code Graph.io()} is one thread handing out work in
     * order, so a task queued after the sheet's own read has to wait for it.
     */
    private void settle() throws InterruptedException {
        shadowOf(Looper.getMainLooper()).idle();
        CountDownLatch behindTheRead = new CountDownLatch(1);
        Graph.io().execute(behindTheRead::countDown);
        behindTheRead.await(10, TimeUnit.SECONDS);
        shadowOf(Looper.getMainLooper()).idle();
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
