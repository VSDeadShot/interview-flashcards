package dev.vsdeadshot.flashcards.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import androidx.appcompat.widget.Toolbar;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.testing.WorkManagerTestInitHelper;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import dev.vsdeadshot.flashcards.R;
import dev.vsdeadshot.flashcards.data.sync.SyncScheduler;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The shell: that it inflates at all, that the bottom bar drives the graph, and that the sync
 * action has somewhere to go.
 *
 * <p>The Stats tab is deliberately never selected here. Reaching it builds the on-disk database
 * through {@code FlashcardsDatabase.get}, whose instance is static and would outlive the test that
 * created it. What this class asserts about that destination is that it exists and that the bar
 * names it, which is the part the shell is responsible for; reading the cache is
 * {@code StatsViewModelTest}'s job.
 */
@RunWith(RobolectricTestRunner.class)
// FlashcardsApp is kept out of this: Robolectric does not create the app's content providers, so
// androidx.startup never initialises WorkManager and onCreate's call to it throws. The test
// WorkManager set up below is what the sync action talks to instead.
@Config(application = Application.class)
public class MainActivityTest {

    private MainActivity activity;

    @Before
    public void setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(RuntimeEnvironment.getApplication());
        activity = Robolectric.buildActivity(MainActivity.class).setup().get();
    }

    @Test
    public void theAppOpensOnTheStudyQueue() {
        assertEquals("study is where somebody who opened this app wants to be",
                R.id.studyFragment, currentDestination());
    }

    @Test
    public void theBottomBarNamesEveryDestinationInTheGraph() {
        BottomNavigationView bottomNav = activity.findViewById(R.id.bottom_nav);

        assertEquals(3, bottomNav.getMenu().size());
        // The menu item ids are the only link between bottom_nav.xml and nav_graph.xml, and
        // nothing about a mismatch fails the build: the tab simply stops working when tapped.
        // This is the assertion that catches a rename made in one file and not the other.
        assertNotNull(navController().getGraph().findNode(R.id.studyFragment));
        assertNotNull(navController().getGraph().findNode(R.id.cardListFragment));
        assertNotNull(navController().getGraph().findNode(R.id.statsFragment));
    }

    @Test
    public void tappingATabMovesTheGraphToIt() {
        BottomNavigationView bottomNav = activity.findViewById(R.id.bottom_nav);

        bottomNav.setSelectedItemId(R.id.cardListFragment);

        assertEquals(R.id.cardListFragment, currentDestination());
    }

    @Test
    public void theSyncActionEnqueuesAnImmediateRun() throws Exception {
        Toolbar toolbar = activity.findViewById(R.id.toolbar);
        assertNotNull("the action is missing from the bar it was inflated into",
                toolbar.getMenu().findItem(R.id.action_sync_now));

        // Dispatched through the menu rather than by calling onOptionsItemSelected directly, so
        // what is exercised is the path a tap actually takes.
        toolbar.getMenu().performIdentifierAction(R.id.action_sync_now, 0);

        List<WorkInfo> enqueued = WorkManager.getInstance(RuntimeEnvironment.getApplication())
                .getWorkInfosForUniqueWork(SyncScheduler.IMMEDIATE_WORK)
                .get();
        assertEquals("the action's whole job is to ask for one run, now", 1, enqueued.size());
        WorkInfo.State state = enqueued.get(0).getState();
        assertTrue("a queued run is either waiting for a network or already going",
                state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.RUNNING);
    }

    private int currentDestination() {
        return navController().getCurrentDestination().getId();
    }

    private NavController navController() {
        NavHostFragment host = (NavHostFragment)
                activity.getSupportFragmentManager().findFragmentById(R.id.nav_host);
        return host.getNavController();
    }
}
