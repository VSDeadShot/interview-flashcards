package dev.vsdeadshot.flashcards.ui.stats;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import dev.vsdeadshot.flashcards.R;

/**
 * Two numbers read from the cache.
 *
 * <p>Not the stats screen — that is a later slice, and needs the topic breakdown and the streak's
 * "as of" and its absence. What this is for is proving the read works end to end: the figures
 * appear without a network, and they change on their own when a sync writes.
 */
public final class StatsFragment extends Fragment {

    public StatsFragment() {
        super(R.layout.fragment_stats);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        TextView totalCards = view.findViewById(R.id.stats_total_cards);
        TextView dueToday = view.findViewById(R.id.stats_due_today);

        StatsViewModel model = new ViewModelProvider(this).get(StatsViewModel.class);
        // getViewLifecycleOwner, not the fragment: a fragment outlives its view when it is
        // detached, and an observer bound to the fragment would write into views that are gone.
        model.stats().observe(getViewLifecycleOwner(), stats -> {
            totalCards.setText(getResources().getQuantityString(
                    R.plurals.stats_total_cards, stats.totalCards(), stats.totalCards()));
            dueToday.setText(getResources().getQuantityString(
                    R.plurals.stats_due_today, stats.dueToday(), stats.dueToday()));
        });
    }
}
