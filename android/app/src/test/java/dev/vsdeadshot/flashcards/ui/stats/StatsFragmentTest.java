package dev.vsdeadshot.flashcards.ui.stats;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.room.Room;
import androidx.work.testing.WorkManagerTestInitHelper;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import dev.vsdeadshot.flashcards.R;
import dev.vsdeadshot.flashcards.data.local.CardEntity;
import dev.vsdeadshot.flashcards.data.local.FlashcardsDatabase;
import dev.vsdeadshot.flashcards.data.local.StatsSnapshotEntity;
import dev.vsdeadshot.flashcards.data.local.TopicEntity;
import dev.vsdeadshot.flashcards.ui.Graph;
import dev.vsdeadshot.flashcards.ui.MainActivity;
import java.time.Duration;
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
 * The screen as it is actually assembled — no injected database, no direct executor.
 *
 * <p>{@code StatsViewModelTest} pins what the seam does; this pins that the fragment reaches it,
 * and what it draws once it has. Two of those are worth more than the figures themselves: that a
 * streak nobody has fetched shows no number at all rather than a zero, and that a topic holding no
 * cards still gets a row.
 *
 * <p>The part neither the compiler nor the view model's test can vouch for is
 * {@code new ViewModelProvider(this).get(StatsViewModel.class)}: the default factory has to build
 * an {@code AndroidViewModel}, and if it cannot, the failure is a crash on the first tab a person
 * taps. There is no emulator on this machine, so a test is the only thing standing between that
 * and finding out later.
 *
 * <p>The cache it reads is installed rather than built by {@code FlashcardsDatabase.get}: that
 * instance is static and on disk, and would outlive the test that created it — leaving rows behind
 * for whichever test class ran next. Installing one also means this test can seed it.
 */
@RunWith(RobolectricTestRunner.class)
// FlashcardsApp is kept out of this: Robolectric does not create the app's content providers, so
// androidx.startup never initialises WorkManager and onCreate's call to it throws.
@Config(application = Application.class)
public class StatsFragmentTest {

    private FlashcardsDatabase db;
    private MainActivity activity;

    @Before
    public void setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(RuntimeEnvironment.getApplication());
        db = Room.inMemoryDatabaseBuilder(
                        RuntimeEnvironment.getApplication(), FlashcardsDatabase.class)
                .allowMainThreadQueries()
                .build();
        Graph.installDatabase(db);
    }

    @After
    public void tearDown() {
        Graph.reset();
        db.close();
    }

    @Test
    public void openingTheTabPutsFiguresFromTheCacheOnScreen() throws Exception {
        cacheTopic(1L, "Operating Systems");
        cacheCard(1L, 1L, LocalDate.now().minusDays(1));
        cacheCard(2L, 1L, LocalDate.now().plusDays(4));
        db.stats().recordReview(LocalDate.now());
        db.stats().recordReview(LocalDate.now());

        openStats();

        assertEquals("1", text(R.id.stats_due_today));
        assertEquals("2", text(R.id.stats_reviewed_today));
        assertEquals("2", text(R.id.stats_total_cards));
    }

    /**
     * The one figure this client cannot work out for itself. Until a sync has fetched it there is
     * no number to show, and a zero shown to somebody a month into a run would be a lie told
     * confidently — so the number is not drawn at all.
     */
    @Test
    public void aStreakThatHasNeverBeenFetchedShowsNoNumberAtAll() throws Exception {
        openStats();

        assertEquals("nothing has been fetched, so there is nothing to claim",
                View.GONE, activity.findViewById(R.id.stats_streak_value).getVisibility());
        assertEquals(activity.getString(R.string.stats_streak_unknown),
                text(R.id.stats_streak_detail));
    }

    @Test
    public void theServersStreakIsShownWithHowStaleItIs() throws Exception {
        cacheStreak(5, Instant.now().minus(Duration.ofHours(2)));

        openStats();

        assertEquals(View.VISIBLE,
                activity.findViewById(R.id.stats_streak_value).getVisibility());
        assertEquals("5 days", text(R.id.stats_streak_value));
        String asOf = text(R.id.stats_streak_detail);
        String prefix = activity.getString(R.string.stats_streak_as_of, "");
        assertTrue("a streak with no as-of claims to be current, and this one is not",
                asOf.startsWith(prefix.trim()));
        assertTrue("the placeholder is there to be filled with when it was fetched",
                asOf.length() > prefix.length());
    }

    /**
     * A topic whose cards are all archived still exists, and nothing deletes topics. Dropping its
     * row when the count reaches zero would read as the topic itself having been deleted.
     */
    @Test
    public void everyTopicGetsARowIncludingOneHoldingNoCards() throws Exception {
        cacheTopic(1L, "Operating Systems");
        cacheTopic(2L, "Databases");
        cacheCard(1L, 1L, LocalDate.now().minusDays(1));
        cacheCard(2L, 1L, LocalDate.now().plusDays(4));

        openStats();

        LinearLayout topics = activity.findViewById(R.id.stats_topics);
        assertEquals(2, topics.getChildCount());
        // The DAO orders by name, so Databases comes first.
        assertEquals("Databases", rowText(topics, 0, R.id.topic_name));
        assertEquals("0 due of 0 cards", rowText(topics, 0, R.id.topic_counts));
        assertEquals("Operating Systems", rowText(topics, 1, R.id.topic_name));
        assertEquals("1 due of 2 cards", rowText(topics, 1, R.id.topic_counts));
    }

    /**
     * A bar whose maximum is the topic's card count has no defined length for a topic holding
     * none, which is exactly the row the test above insists on drawing.
     */
    @Test
    public void aTopicWithNoCardsDrawsAnEmptyBarRatherThanABrokenOne() throws Exception {
        cacheTopic(1L, "Databases");

        openStats();

        LinearLayout topics = activity.findViewById(R.id.stats_topics);
        LinearProgressIndicator bar =
                topics.getChildAt(0).findViewById(R.id.topic_progress);
        assertEquals(0, bar.getProgress());
        assertTrue("a maximum of zero is a bar with nothing to fill", bar.getMax() > 0);
    }

    @Test
    public void aCacheWithNoTopicsSaysSoInsteadOfAnEmptyBreakdown() throws Exception {
        openStats();

        assertEquals(View.VISIBLE,
                activity.findViewById(R.id.stats_no_topics).getVisibility());
        LinearLayout topics = activity.findViewById(R.id.stats_topics);
        assertEquals(0, topics.getChildCount());
    }

    // ---- fixtures -----------------------------------------------------------------------------

    private void openStats() throws InterruptedException {
        activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        BottomNavigationView bottomNav = activity.findViewById(R.id.bottom_nav);
        bottomNav.setSelectedItemId(R.id.statsFragment);
        settle();
    }

    private String text(int id) {
        return ((TextView) activity.findViewById(id)).getText().toString();
    }

    private static String rowText(LinearLayout topics, int position, int id) {
        return ((TextView) topics.getChildAt(position).findViewById(id)).getText().toString();
    }

    /**
     * Waits for the read, then delivers it.
     *
     * <p>{@code Graph.io()} is one thread and hands out work in order, so a task queued after the
     * view model's read has to wait for it — which is what makes this a wait rather than a sleep.
     * The value then sits on the main looper, which Robolectric does not run unasked.
     */
    private void settle() throws InterruptedException {
        shadowOf(Looper.getMainLooper()).idle();
        CountDownLatch behindTheRead = new CountDownLatch(1);
        Graph.io().execute(behindTheRead::countDown);
        behindTheRead.await(10, TimeUnit.SECONDS);
        shadowOf(Looper.getMainLooper()).idle();
    }

    private void cacheCard(long id, long topicId, LocalDate dueDate) {
        CardEntity card = new CardEntity();
        card.id = id;
        card.serverId = id;
        card.topicId = topicId;
        card.front = "front " + id;
        card.back = "back " + id;
        card.easeFactor = 2.5d;
        card.intervalDays = 1;
        card.repetitions = 1;
        card.dueDate = dueDate;
        db.cards().upsertAll(List.of(card));
    }

    private void cacheTopic(long id, String name) {
        TopicEntity topic = new TopicEntity();
        topic.id = id;
        topic.name = name;
        topic.slug = name.toLowerCase().replace(' ', '-');
        topic.createdAt = Instant.parse("2026-01-01T00:00:00Z");
        db.topics().upsertAll(List.of(topic));
    }

    private void cacheStreak(int days, Instant fetchedAt) {
        StatsSnapshotEntity snapshot = new StatsSnapshotEntity();
        snapshot.currentStreakDays = days;
        snapshot.fetchedAt = fetchedAt;
        db.stats().saveSnapshot(snapshot);
    }
}
