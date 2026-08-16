package dev.vsdeadshot.flashcards.ui.stats;

import static org.junit.Assert.assertEquals;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.os.Looper;
import android.widget.TextView;
import androidx.room.Room;
import androidx.work.testing.WorkManagerTestInitHelper;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import dev.vsdeadshot.flashcards.R;
import dev.vsdeadshot.flashcards.data.local.CardEntity;
import dev.vsdeadshot.flashcards.data.local.FlashcardsDatabase;
import dev.vsdeadshot.flashcards.ui.Graph;
import dev.vsdeadshot.flashcards.ui.MainActivity;
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
 * <p>{@code StatsViewModelTest} pins what the seam does; this pins that the fragment reaches it.
 * The part neither the compiler nor that test can vouch for is
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
        cacheCard(1L, LocalDate.now().minusDays(1));
        cacheCard(2L, LocalDate.now().plusDays(4));
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        BottomNavigationView bottomNav = activity.findViewById(R.id.bottom_nav);

        bottomNav.setSelectedItemId(R.id.statsFragment);
        settle();

        TextView totalCards = activity.findViewById(R.id.stats_total_cards);
        TextView dueToday = activity.findViewById(R.id.stats_due_today);
        assertEquals("2 cards", totalCards.getText().toString());
        assertEquals("1 due today", dueToday.getText().toString());
    }

    private void cacheCard(long id, LocalDate dueDate) {
        CardEntity card = new CardEntity();
        card.id = id;
        card.serverId = id;
        card.topicId = 1L;
        card.front = "front " + id;
        card.back = "back " + id;
        card.easeFactor = 2.5d;
        card.intervalDays = 1;
        card.repetitions = 1;
        card.dueDate = dueDate;
        db.cards().upsertAll(List.of(card));
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
}
