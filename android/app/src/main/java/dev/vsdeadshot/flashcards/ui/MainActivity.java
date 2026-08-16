package dev.vsdeadshot.flashcards.ui;

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import dev.vsdeadshot.flashcards.R;
import dev.vsdeadshot.flashcards.data.sync.SyncScheduler;

/**
 * The one activity: a toolbar, a fragment container, and a bottom bar.
 *
 * <p>Single-activity because the three destinations are peers a person moves between constantly,
 * and a back stack shared across them is the behaviour the bottom bar already implies.
 */
public final class MainActivity extends AppCompatActivity {

    private NavController navController;
    private AppBarConfiguration appBarConfiguration;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setSupportActionBar(findViewById(R.id.toolbar));

        // findFragmentById rather than the cast-free NavHostFragment.findNavController: a
        // FragmentContainerView creates its fragment when it is attached, so the controller does
        // not exist until after setContentView has run, and this is the supported way to reach it.
        NavHostFragment host =
                (NavHostFragment) getSupportFragmentManager().findFragmentById(R.id.nav_host);
        navController = host.getNavController();

        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav);
        NavigationUI.setupWithNavController(bottomNav, navController);
        // Built from the bottom bar's own menu, so every tab counts as a top-level destination
        // and none of them shows an up arrow. Built from the graph instead, only the start
        // destination would, and the other two tabs would offer to go "up" to Study.
        appBarConfiguration = new AppBarConfiguration.Builder(bottomNav.getMenu()).build();
        NavigationUI.setupActionBarWithNavController(this, navController, appBarConfiguration);
    }

    /**
     * Makes the up arrow do something.
     *
     * <p>The card editor is the one destination outside the bottom bar's menu, so
     * {@link AppBarConfiguration} already treats it as not top level and draws the arrow. Without
     * this the arrow is decoration.
     */
    @Override
    public boolean onSupportNavigateUp() {
        // NavigationUI.navigateUp, not NavController.navigateUp(AppBarConfiguration):
        // the latter is a Kotlin extension and does not exist from Java.
        return NavigationUI.navigateUp(navController, appBarConfiguration)
                || super.onSupportNavigateUp();
    }

    @Override
    public boolean onCreateOptionsMenu(@NonNull Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_sync_now) {
            // The first caller SyncScheduler.syncNow has had. The periodic request is the safety
            // net; this is what makes a change visible without waiting out the interval — and,
            // once the study screen lands, what will run after a review is recorded.
            SyncScheduler.syncNow(this);
            Toast.makeText(this, R.string.sync_queued, Toast.LENGTH_SHORT).show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
