package dev.vsdeadshot.flashcards.ui.stats;

import static org.junit.Assert.assertEquals;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.os.Looper;
import android.widget.TextView;
import androidx.work.testing.WorkManagerTestInitHelper;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import dev.vsdeadshot.flashcards.R;
import dev.vsdeadshot.flashcards.ui.Graph;
import dev.vsdeadshot.flashcards.ui.MainActivity;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
 * <p><strong>This is the one test class that touches the real database.</strong> Going through
 * {@code Graph} means {@code FlashcardsDatabase.get} builds its static, on-disk instance inside
 * Robolectric's sandbox. Nothing else in the suite calls {@code get}, so the instance it leaves
 * behind is not something another test can pick up.
 */
@RunWith(RobolectricTestRunner.class)
// FlashcardsApp is kept out of this: Robolectric does not create the app's content providers, so
// androidx.startup never initialises WorkManager and onCreate's call to it throws.
@Config(application = Application.class)
public class StatsFragmentTest {

    @Before
    public void setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(RuntimeEnvironment.getApplication());
    }

    @Test
    public void openingTheTabPutsFiguresFromTheCacheOnScreen() throws Exception {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        BottomNavigationView bottomNav = activity.findViewById(R.id.bottom_nav);

        bottomNav.setSelectedItemId(R.id.statsFragment);
        settle();

        TextView totalCards = activity.findViewById(R.id.stats_total_cards);
        TextView dueToday = activity.findViewById(R.id.stats_due_today);
        // An empty cache is the honest answer for a database nothing has synced into, so what is
        // asserted is that the read completed and reached the views — not a particular count.
        assertEquals("0 cards", totalCards.getText().toString());
        assertEquals("0 due today", dueToday.getText().toString());
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
