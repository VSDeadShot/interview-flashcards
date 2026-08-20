package dev.vsdeadshot.flashcards.ui.stats;

import android.animation.ObjectAnimator;
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
import androidx.navigation.fragment.NavHostFragment;
import androidx.transition.AutoTransition;
import androidx.transition.TransitionManager;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.transition.MaterialSharedAxis;
import dev.vsdeadshot.flashcards.R;
import dev.vsdeadshot.flashcards.data.StatsRepository.StatsView;
import dev.vsdeadshot.flashcards.data.local.TopicStatsRow;
import dev.vsdeadshot.flashcards.ui.Motion;
import dev.vsdeadshot.flashcards.ui.cards.CardListFragment;
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

    /** Each bar growing to its share, behind the block it sits in. */
    private static final long BAR_MS = 620L;

    private static final long BAR_DELAY_MS = 260L;

    private static final long BAR_STAGGER_MS = 70L;

    /** The streak card growing to make room for a figure it has just been told. */
    private static final long STREAK_MS = 280L;

    /**
     * Whether the entrance has already played on this view.
     *
     * <p>The screen redraws whenever a sync writes, which is often and is not an arrival. Without
     * this the blocks would rise again every time the number under them changed.
     */
    private boolean entered;

    /** Whether the streak was showing a number last time this drew. */
    private boolean hadStreak;

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
        // Put back after a drill-in borrowed it. This runs again when the screen returns, so the
        // shared axis openTopic installs is only in force for the one navigation that wants it.
        Motion.peerDestination(this);
        entered = false;

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
        enter(view);
    }

    /**
     * Three blocks rise in order, once.
     *
     * <p>Three, not eleven. The card is the thing that arrives; animating each number inside it
     * separately would be motion for its own sake, and the figures are read by comparing them to
     * each other, which is harder while they are all still moving.
     *
     * <p>None of them counts up from zero either. A number rolling to nine is half a second in
     * the way of that comparison, and it animates through values that were never true.
     */
    private void enter(@NonNull View view) {
        if (entered) {
            return;
        }
        entered = true;
        Motion.rise(view.findViewById(R.id.stats_figures), 40L);
        Motion.rise(view.findViewById(R.id.stats_streak), 110L);
        Motion.rise(view.findViewById(R.id.stats_by_topic), 180L);
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
            hadStreak = false;
            return;
        }

        // A sync has brought the figure for the first time. The card grows to make room and the
        // sentence changes underneath it; nothing is replaced where it stood, because until this
        // moment there was no number standing there.
        if (!hadStreak && entered) {
            AutoTransition arrival = new AutoTransition();
            arrival.setDuration(STREAK_MS);
            arrival.setInterpolator(Motion.FAST_OUT_SLOW_IN);
            TransitionManager.beginDelayedTransition(
                    (ViewGroup) view.findViewById(R.id.stats_streak), arrival);
        }
        hadStreak = true;

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
        boolean none = stats.byTopic().isEmpty();
        view.findViewById(R.id.stats_no_topics).setVisibility(none ? View.VISIBLE : View.GONE);
        // Hidden as well as empty. A card with nothing in it is a rounded rectangle of surface
        // sitting under a sentence explaining why there is nothing to put in it.
        view.findViewById(R.id.stats_topics_card).setVisibility(none ? View.GONE : View.VISIBLE);

        LayoutInflater inflater = LayoutInflater.from(requireContext());
        int position = 0;
        for (TopicStatsRow topic : stats.byTopic()) {
            container.addView(topicRow(inflater, container, topic, position));
            position++;
        }
    }

    private View topicRow(LayoutInflater inflater, ViewGroup container,
            @NonNull TopicStatsRow topic, int position) {
        View row = inflater.inflate(R.layout.item_topic_stats, container, false);
        ((TextView) row.findViewById(R.id.topic_name)).setText(topic.name);
        ((TextView) row.findViewById(R.id.topic_counts)).setText(getResources().getQuantityString(
                R.plurals.stats_topic_counts, topic.total, topic.due, topic.total));

        // The bar and the counts are decoration to anything that cannot see them; where the row
        // goes is the row.
        row.setContentDescription(getString(R.string.stats_topic_open, topic.name));
        Motion.press(row);
        row.setOnClickListener(tapped -> openTopic(topic.topicId));

        LinearProgressIndicator progress = row.findViewById(R.id.topic_progress);
        // A topic can hold no cards at all — the DAO lists every topic on purpose, because one
        // that vanished when its last card was archived would look deleted. A max of zero is a
        // bar with no defined length, so the floor of one leaves it empty instead.
        progress.setMax(Math.max(topic.total, 1));
        fill(progress, topic.total - topic.due, position);
        return row;
    }

    /**
     * Grows a bar from empty to its share.
     *
     * <p>An animator rather than {@code setProgressCompat(value, true)}, which animates on its own
     * schedule: these are staggered behind the block they sit in, and the delay is the whole
     * reason they read as one row of bars filling rather than five things twitching at once.
     *
     * <p>A topic with no cards stays empty. There is nothing to divide by, and a full bar would
     * be the wrong answer to a question about how much of it is under control.
     */
    private void fill(@NonNull LinearProgressIndicator progress, int value, int position) {
        progress.setProgress(0);
        if (value <= 0) {
            return;
        }
        ObjectAnimator grow = ObjectAnimator.ofInt(progress, "progress", 0, value);
        grow.setDuration(BAR_MS);
        grow.setStartDelay(BAR_DELAY_MS + position * BAR_STAGGER_MS);
        grow.setInterpolator(Motion.LINEAR_OUT_SLOW_IN);
        grow.start();
    }

    /**
     * Opens the cards this row counted.
     *
     * <p>This crosses top-level destinations, which is the part worth being careful about. Shared
     * axis Z rather than the fade the three tabs use between themselves: a drill-in from a count
     * into the things counted goes <em>into</em> something, and a fade says the two are beside
     * each other. The exit is put back in {@link #onViewCreated}, which runs again when this
     * screen returns.
     *
     * <p>Nothing here moves the bottom bar. NavigationUI listens for destination changes rather
     * than for taps, so the indicator follows on its own and cannot be left lit on Stats while
     * the card list is on screen.
     */
    private void openTopic(long topicId) {
        setExitTransition(new MaterialSharedAxis(MaterialSharedAxis.Z, true));
        setReenterTransition(new MaterialSharedAxis(MaterialSharedAxis.Z, false));

        Bundle args = new Bundle();
        args.putLong(CardListFragment.ARG_TOPIC_ID, topicId);
        NavHostFragment.findNavController(this)
                .navigate(R.id.action_stats_to_cardList, args);
    }

    private static void setNumber(
            @NonNull View view, int id, NumberFormat numbers, int value) {
        ((TextView) view.findViewById(id)).setText(numbers.format(value));
    }
}
