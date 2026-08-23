package dev.vsdeadshot.flashcards.ui.cards;

import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.room.Room;
import androidx.work.testing.WorkManagerTestInitHelper;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import dev.vsdeadshot.flashcards.R;
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
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Opening the card list and reading rows out of it, shared by the two tests that do.
 *
 * <p>Extracted rather than copied: both files ask the same six questions of the same screen, and
 * a second copy of {@code settle()} that drifts from this one would go unnoticed until a test
 * started passing for the wrong reason.
 *
 * <p>The cache is installed rather than built by {@code FlashcardsDatabase.get}: that instance is
 * static and on disk, and would outlive the test that created it — leaving rows behind for
 * whichever test class ran next.
 */
@RunWith(RobolectricTestRunner.class)
// FlashcardsApp is kept out of this: Robolectric does not create the app's content providers, so
// androidx.startup never initialises WorkManager and onCreate's call to it throws.
@Config(application = Application.class)
public abstract class CardListTestSupport {

    /** How many idle-and-pause cycles {@link #awaitDiff} gives the diff pool. */
    private static final int DIFF_ATTEMPTS = 10;

    private static final long DIFF_PAUSE_MS = 5L;

    protected FlashcardsDatabase db;

    @Before
    public void openCache() {
        WorkManagerTestInitHelper.initializeTestWorkManager(RuntimeEnvironment.getApplication());
        db = Room.inMemoryDatabaseBuilder(
                        RuntimeEnvironment.getApplication(), FlashcardsDatabase.class)
                .allowMainThreadQueries()
                .build();
        Graph.installDatabase(db);
        cacheTopic();
    }

    @After
    public void closeCache() {
        Graph.reset();
        db.close();
    }

    protected RecyclerView openCardsTab() throws InterruptedException {
        MainActivity activity = openActivity();
        RecyclerView list = activity.findViewById(R.id.cards_list);
        // Nothing lays out a RecyclerView in a Robolectric activity, so the rows have to be asked
        // for. measure/layout is what makes the adapter bind them.
        list.measure(0, 0);
        list.layout(0, 0, 1000, 2000);
        return list;
    }

    protected MainActivity openActivity() throws InterruptedException {
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
    protected void settle() throws InterruptedException {
        shadowOf(Looper.getMainLooper()).idle();
        CountDownLatch behindTheRead = new CountDownLatch(1);
        Graph.io().execute(behindTheRead::countDown);
        behindTheRead.await(10, TimeUnit.SECONDS);
        shadowOf(Looper.getMainLooper()).idle();
        awaitDiff();
    }

    /**
     * Waits for {@code ListAdapter} to finish diffing.
     *
     * <p>The read above is not the last thing that has to happen. {@code submitList} computes its
     * diff on {@code AsyncDifferConfig}'s own background pool and posts the result back to the
     * main thread, and nothing here has a handle on that pool to await — so idling the looper
     * once can run ahead of a diff that has not been posted yet, and the assertion then reads a
     * list that has not changed. This surfaced as a rare failure in
     * {@code choosingATopicLeavesOnlyItsCards} rather than a consistent one, which is what a race
     * looks like from the outside.
     *
     * <p>A real pause between idles is what lets the pool run: {@code idleFor} advances the
     * shadow clock and does not yield to another thread. Bounded, so a genuine hang still fails
     * rather than looping.
     */
    private void awaitDiff() throws InterruptedException {
        for (int attempt = 0; attempt < DIFF_ATTEMPTS; attempt++) {
            Thread.sleep(DIFF_PAUSE_MS);
            shadowOf(Looper.getMainLooper()).idle();
        }
    }

    /** Re-lays the list out, which is what binds rows the last change added. */
    protected void relayout(RecyclerView list) {
        list.measure(0, 0);
        list.layout(0, 0, 1000, 2000);
    }

    protected static View row(RecyclerView list, int position) {
        return list.getLayoutManager().findViewByPosition(position);
    }

    protected static String text(RecyclerView list, int position, int id) {
        return ((TextView) row(list, position).findViewById(id)).getText().toString();
    }

    protected static int visibility(RecyclerView list, int position, int id) {
        return row(list, position).findViewById(id).getVisibility();
    }

    protected void cachePulledCard(long id, String front) {
        CardEntity pulled = new CardEntity();
        pulled.id = id;
        pulled.serverId = id;
        pulled.topicId = 1L;
        pulled.front = front;
        pulled.back = "back";
        pulled.dueDate = LocalDate.now();
        db.cards().upsertAll(List.of(pulled));
    }

    protected void cacheTopic() {
        TopicEntity topic = new TopicEntity();
        topic.id = 1L;
        topic.name = "Operating Systems";
        topic.slug = "operating-systems";
        topic.createdAt = Instant.parse("2026-01-01T00:00:00Z");
        db.topics().upsertAll(List.of(topic));
    }
}
