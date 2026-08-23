package dev.vsdeadshot.flashcards.ui;

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.ActionMenuView;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import dev.vsdeadshot.flashcards.R;
import dev.vsdeadshot.flashcards.data.auth.TokenStore.SignedOutReason;
import dev.vsdeadshot.flashcards.data.sync.SyncScheduler;
import dev.vsdeadshot.flashcards.ui.auth.AuthViewModel;

/**
 * The one activity: a toolbar, a fragment container, and a bottom bar.
 *
 * <p>Single-activity because the three destinations are peers a person moves between constantly,
 * and a back stack shared across them is the behaviour the bottom bar already implies.
 */
public final class MainActivity extends AppCompatActivity {

    private NavController navController;
    private AppBarConfiguration appBarConfiguration;
    private AuthViewModel auth;

    /**
     * The last auth state seen, read by {@link #onPrepareOptionsMenu} rather than looked up.
     * The menu is prepared on the main thread and the state arrives on whichever thread wrote
     * the token store, so the observer is the one place the two meet.
     */
    private boolean signedIn;

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

        springLoad(bottomNav);
        watchSession();
    }

    /**
     * Puts a dead session somewhere a person can see it.
     *
     * <p>This is the whole of the answer to a problem the sync has had since it landed: a
     * rejected credential cannot reach the UI through {@code WorkInfo}, because WorkManager
     * stores no output data for periodic work and routes success and failure through the same
     * reset. Before tokens that gap cost little — a wrong API key was a broken build, noticed
     * immediately and by one person. A token expires after thirty days on a device that has been
     * working perfectly, and the only symptom is an outbox that quietly stops draining.
     */
    private void watchSession() {
        TextView banner = findViewById(R.id.auth_banner);
        Motion.press(banner);
        banner.setOnClickListener(tapped -> openSignIn());

        auth = new ViewModelProvider(this).get(AuthViewModel.class);
        auth.state().observe(this, state -> {
            signedIn = state.signedIn();
            if (!state.signedIn()) {
                banner.setText(state.reason() == SignedOutReason.SESSION_EXPIRED
                        ? R.string.auth_banner_expired
                        : R.string.auth_banner_never);
            }
            drawBanner();
            // The overflow's two items are chosen from the same state, and it may already have
            // been built by the time this first fires.
            invalidateOptionsMenu();
        });

        // The banner is also wrong on one destination regardless of the session, so the two
        // reasons to hide it are combined in one place.
        navController.addOnDestinationChangedListener(
                (controller, destination, arguments) -> drawBanner());
    }

    /**
     * Shows the banner only when it has something to say that is not already on screen.
     *
     * <p>Hidden on the sign-in destination itself, where "tap to sign in" is an instruction to
     * do the thing somebody is already doing — and where it sits directly above a heading that
     * says the same word. Hidden, not disabled: a strip that stays and stops working reads as a
     * bug rather than as a decision.
     */
    private void drawBanner() {
        View banner = findViewById(R.id.auth_banner);
        boolean onSignIn = navController.getCurrentDestination() != null
                && navController.getCurrentDestination().getId() == R.id.signInFragment;
        banner.setVisibility(signedIn || onSignIn ? View.GONE : View.VISIBLE);
    }

    /**
     * Opens the sign-in screen, unless it is already what somebody is looking at.
     *
     * <p>The guard is not tidiness. Both ways in — the banner and the overflow — stay reachable
     * while it is on screen, and without this each tap would stack a second copy of it on the
     * back stack for somebody to press back through afterwards.
     */
    private void openSignIn() {
        if (navController.getCurrentDestination() != null
                && navController.getCurrentDestination().getId() == R.id.signInFragment) {
            return;
        }
        navController.navigate(R.id.signInFragment);
    }

    /**
     * Gives the three tabs the app's press feedback.
     *
     * <p>Posted rather than called outright: BottomNavigationView builds its item views when it
     * inflates its menu, which has not happened while the layout is still being read.
     *
     * <p>A tab scales less than a button does. It is a third of the screen wide, and the amount
     * that reads as a press on a pill reads as a lurch on something that size.
     */
    private void springLoad(BottomNavigationView bottomNav) {
        bottomNav.post(() -> {
            // The one child is the menu view; its children are the items, in menu order.
            ViewGroup items = (ViewGroup) bottomNav.getChildAt(0);
            for (int i = 0; i < items.getChildCount(); i++) {
                Motion.press(items.getChildAt(i), Motion.PRESS_TAB);
            }
        });
    }

    /**
     * Gives the toolbar's actions the same press as everything else.
     *
     * <p>Done on every menu preparation rather than once, because the menu is rebuilt whenever a
     * fragment adds or removes a MenuProvider - the card list's generate action arrives and
     * leaves that way, and an item added after this ran would be the one control in the app that
     * did not answer a finger. Re-attaching replaces a listener rather than stacking one.
     */
    @Override
    public boolean onPrepareOptionsMenu(@NonNull Menu menu) {
        // Exactly one of the two is ever offered. A single item retitled would be one thing
        // whose label and whose behaviour had to be kept in step by hand.
        MenuItem signIn = menu.findItem(R.id.action_sign_in);
        MenuItem signOut = menu.findItem(R.id.action_sign_out);
        if (signIn != null && signOut != null) {
            signIn.setVisible(!signedIn);
            signOut.setVisible(signedIn);
        }

        // Posted for the same reason the tabs are: the item views do not exist until the menu
        // has been laid out, and preparation is what triggers that rather than the end of it.
        findViewById(R.id.toolbar).post(this::springLoadToolbar);
        return super.onPrepareOptionsMenu(menu);
    }

    private void springLoadToolbar() {
        ViewGroup toolbar = findViewById(R.id.toolbar);
        for (int i = 0; i < toolbar.getChildCount(); i++) {
            View child = toolbar.getChildAt(i);
            if (child instanceof ActionMenuView actions) {
                for (int action = 0; action < actions.getChildCount(); action++) {
                    Motion.press(actions.getChildAt(action));
                }
            } else if (child instanceof ImageButton) {
                // The up arrow. It is a child of the toolbar rather than of the menu, being
                // navigation rather than an action, so it is not reached by the loop above.
                Motion.press(child);
            }
        }
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
        if (item.getItemId() == R.id.action_sign_in) {
            openSignIn();
            return true;
        }
        if (item.getItemId() == R.id.action_sign_out) {
            // No confirmation. Nothing is lost by signing out — the cache stays, the outbox
            // stays, and everything queued is sent when somebody signs in again — so a dialog
            // would be asking about a decision that costs nothing to undo.
            auth.signOut();
            return true;
        }
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
