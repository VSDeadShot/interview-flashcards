package dev.vsdeadshot.flashcards.ui.cards;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.room.Room;
import androidx.work.testing.WorkManagerTestInitHelper;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.color.MaterialColors;
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

/**
 * The list as it is actually assembled — real fragment, real adapter, real {@code Graph}.
 *
 * <p>What this is for beyond assembly is the status label, which is the first time
 * {@code card.syncError} is put in front of a person. A row says at most one thing, and which one
 * it says is decided in the adapter rather than in the query, so nothing else can check it.
 */
@RunWith(RobolectricTestRunner.class)
// FlashcardsApp is kept out of this: Robolectric does not create the app's content providers, so
// androidx.startup never initialises WorkManager and onCreate's call to it throws.
@Config(application = Application.class)
public class CardListFragmentTest {

    private FlashcardsDatabase db;
    private CardRepository repository;

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
    public void everyCardIsListedWithItsTopic() throws Exception {
        cachePulledCard(1L, "What is a deadlock?");
        cachePulledCard(2L, "What is a page fault?");

        RecyclerView list = openCardsTab();

        assertEquals(2, list.getAdapter().getItemCount());
        assertEquals("What is a deadlock?", text(list, 0, R.id.card_front));
        assertEquals("Operating Systems", text(list, 0, R.id.card_topic));
    }

    @Test
    public void aSyncedCardSaysNothingAboutSyncing() throws Exception {
        cachePulledCard(1L, "front");

        RecyclerView list = openCardsTab();

        assertEquals("most rows are synced, and a badge on every one of them would be noise",
                View.GONE, visibility(list, 0, R.id.card_status));
    }

    @Test
    public void aCardWrittenHereIsMarkedUnsent() throws Exception {
        repository.create(1L, "written here", "back");

        RecyclerView list = openCardsTab();

        assertEquals(View.VISIBLE, visibility(list, 0, R.id.card_status));
        assertEquals("Unsent", text(list, 0, R.id.card_status));
    }

    /**
     * The one row worth acting on. It is also unsent, and saying only that would hide the fact
     * that nothing more will be tried until the card is edited.
     */
    @Test
    public void aCardTheServerRefusedIsMarkedRejectedAndInTheErrorColour() throws Exception {
        long cardId = repository.create(1L, "written here", "back").id;
        db.cards().recordSyncFailure(cardId, "No topic with id 42");

        RecyclerView list = openCardsTab();

        assertEquals("Rejected", text(list, 0, R.id.card_status));
        TextView status = (TextView) row(list, 0).findViewById(R.id.card_status);
        assertEquals(MaterialColors.getColor(status, androidx.appcompat.R.attr.colorError),
                status.getCurrentTextColor());
        assertNotEquals("rejected has to look different from merely unsent, not just read"
                        + " differently",
                MaterialColors.getColor(
                        status, com.google.android.material.R.attr.colorOnSurfaceVariant),
                status.getCurrentTextColor());
    }

    /**
     * A card outlives its topic being deleted on the server. A blank line where the topic goes
     * would read as a row that failed to load.
     */
    @Test
    public void aCardWithNoCachedTopicSaysSoRatherThanShowingABlank() throws Exception {
        CardEntity orphan = new CardEntity();
        orphan.id = 9L;
        orphan.serverId = 9L;
        orphan.topicId = 404L;
        orphan.front = "topic went away";
        orphan.back = "back";
        orphan.dueDate = LocalDate.now();
        db.cards().upsertAll(List.of(orphan));

        RecyclerView list = openCardsTab();

        assertEquals("No topic", text(list, 0, R.id.card_topic));
    }

    @Test
    public void anEmptyCacheShowsTheMessageInsteadOfTheList() throws Exception {
        MainActivity activity = openActivity();

        assertEquals(View.VISIBLE, activity.findViewById(R.id.cards_empty).getVisibility());
        assertEquals("an empty message on top of a list about to arrive reads as a failure",
                View.GONE, activity.findViewById(R.id.cards_list).getVisibility());
    }

    // ---- fixtures -----------------------------------------------------------------------------

    private RecyclerView openCardsTab() throws InterruptedException {
        MainActivity activity = openActivity();
        RecyclerView list = activity.findViewById(R.id.cards_list);
        // Nothing lays out a RecyclerView in a Robolectric activity, so the rows have to be asked
        // for. measure/layout is what makes the adapter bind them.
        list.measure(0, 0);
        list.layout(0, 0, 1000, 2000);
        return list;
    }

    private MainActivity openActivity() throws InterruptedException {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        BottomNavigationView bottomNav = activity.findViewById(R.id.bottom_nav);
        bottomNav.setSelectedItemId(R.id.cardListFragment);
        settle();
        return activity;
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

    private static View row(RecyclerView list, int position) {
        return list.getLayoutManager().findViewByPosition(position);
    }

    private static String text(RecyclerView list, int position, int id) {
        return ((TextView) row(list, position).findViewById(id)).getText().toString();
    }

    private static int visibility(RecyclerView list, int position, int id) {
        return row(list, position).findViewById(id).getVisibility();
    }

    private void cachePulledCard(long id, String front) {
        CardEntity pulled = new CardEntity();
        pulled.id = id;
        pulled.serverId = id;
        pulled.topicId = 1L;
        pulled.front = front;
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
