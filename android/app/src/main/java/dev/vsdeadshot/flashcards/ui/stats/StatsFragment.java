package dev.vsdeadshot.flashcards.ui.stats;

import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import dev.vsdeadshot.flashcards.R;
import dev.vsdeadshot.flashcards.ui.Motion;
import dev.vsdeadshot.flashcards.data.StatsRepository.StatsView;
import dev.vsdeadshot.flashcards.data.local.TopicStatsRow;
import java.text.NumberFormat;

/**
 * Where the user stands, answered entirely from the cache.
 *
 * <p>Every figure here except the streak is counted locally on each read, so this screen is
 * correct with the radio off and is correct <em>before</em> the server is, holding cards written
 * and retired on this device that no sync has mentioned yet.
 *
 * <p>The streak is the exception and is treated as one. It cannot be worked out here — its rule
 * skips days on which nothing was due, which needs the whole review history and every card's due
 * date as it stood on each of those days — so it is the server's figure, shown with the time this
 * client last asked, and hidden outright when it has never been fetched.
 */
public final class StatsFragment extends Fragment {

    public StatsFragment() {
        super(R.layout.fragment_stats);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Set here rather than in onViewCreated: a fragment's transitions are read when
        // the transaction that shows it is executed, which is before its view exists.
        Motion.peerDestination(this);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        StatsViewModel model = new ViewModelProvider(this).get(StatsViewModel.class);
        // getViewLifecycleOwner, not the fragment: a fragment outlives its view when it is
        // detached, and an observer bound to the fragment would write into views that are gone.
        model.stats().observe(getViewLifecycleOwner(), stats -> draw(view, stats));
    }

    private void draw(@NonNull View view, @NonNull StatsView stats) {
        NumberFormat numbers = NumberFormat.getIntegerInstance();
        setNumber(view, R.id.stats_due_today, numbers, stats.dueToday());
        setNumber(view, R.id.stats_reviewed_today, numbers, stats.reviewedToday());
        setNumber(view, R.id.stats_total_cards, numbers, stats.totalCards());

        drawStreak(view, stats);
        drawTopics(view, stats);
    }

    /**
     * The number and the sentence beneath it are drawn separately so the number can be absent.
     * Null and zero are different answers: "we have never been told" rendered as a confident zero,
     * to somebody a month into a run, is the one thing this screen must not do.
     */
    private void drawStreak(@NonNull View view, @NonNull StatsView stats) {
        TextView value = view.findViewById(R.id.stats_streak_value);
        TextView detail = view.findViewById(R.id.stats_streak_detail);

        if (!stats.hasStreak()) {
            value.setVisibility(View.GONE);
            detail.setText(R.string.stats_streak_unknown);
            return;
        }

        value.setVisibility(View.VISIBLE);
        value.setText(getResources().getQuantityString(
                R.plurals.stats_streak_days, stats.streakDays(), stats.streakDays()));
        // A streak with no "as of" claims to be current, and this one is only ever as current as
        // the last sync. Relative rather than a timestamp because the question it answers is how
        // stale the figure is, not when it was taken.
        detail.setText(getString(R.string.stats_streak_as_of, DateUtils.getRelativeTimeSpanString(
                stats.streakAsOf().toEpochMilli(),
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS)));
    }

    private void drawTopics(@NonNull View view, @NonNull StatsView stats) {
        LinearLayout container = view.findViewById(R.id.stats_topics);
        container.removeAllViews();
        view.findViewById(R.id.stats_no_topics)
                .setVisibility(stats.byTopic().isEmpty() ? View.VISIBLE : View.GONE);

        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (TopicStatsRow topic : stats.byTopic()) {
            container.addView(topicRow(inflater, container, topic));
        }
    }

    private View topicRow(
            LayoutInflater inflater, ViewGroup container, @NonNull TopicStatsRow topic) {
        View row = inflater.inflate(R.layout.item_topic_stats, container, false);
        ((TextView) row.findViewById(R.id.topic_name)).setText(topic.name);
        ((TextView) row.findViewById(R.id.topic_counts)).setText(getResources().getQuantityString(
                R.plurals.stats_topic_counts, topic.total, topic.due, topic.total));

        LinearProgressIndicator progress = row.findViewById(R.id.topic_progress);
        // A topic can hold no cards at all — the DAO lists every topic on purpose, because one
        // that vanished when its last card was archived would look deleted. A max of zero is a
        // bar with no defined length, so the floor of one leaves it empty instead.
        progress.setMax(Math.max(topic.total, 1));
        progress.setProgress(topic.total - topic.due);
        return row;
    }

    private static void setNumber(
            @NonNull View view, int id, NumberFormat numbers, int value) {
        ((TextView) view.findViewById(id)).setText(numbers.format(value));
    }
}
