package dev.vsdeadshot.flashcards.ui.cards;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import dev.vsdeadshot.flashcards.R;
import dev.vsdeadshot.flashcards.data.local.CandidateEntity;
import dev.vsdeadshot.flashcards.data.local.CardEntity;
import dev.vsdeadshot.flashcards.data.local.TopicEntity;
import dev.vsdeadshot.flashcards.ui.MainActivity;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

/**
 * Scoping the list to one topic, and what the screen says when that topic holds nothing.
 *
 * <p>Separate from {@code CardListFragmentTest}, which is about what a row says. This is about
 * what the list contains, which is a different question and needs a second topic to ask.
 */
public class CardListFilterTest extends CardListTestSupport {

    /** Longer than the list leaving and arriving, which a filter change puts between the two. */
    private static final Duration SWAP = Duration.ofMillis(500);

    @Before
    public void cacheASecondTopic() {
        TopicEntity dbms = new TopicEntity();
        dbms.id = 2L;
        dbms.name = "DBMS";
        dbms.slug = "dbms";
        dbms.createdAt = Instant.parse("2026-01-01T00:00:00Z");
        db.topics().upsertAll(List.of(dbms));
    }

    @Test
    public void everyTopicGetsAChipAndAllOfThemComesFirst() throws Exception {
        MainActivity activity = openActivity();
        relayoutList(activity);

        ChipGroup chips = activity.findViewById(R.id.cards_filter);
        assertEquals("one for each topic, plus the one that clears the filter",
                3, chips.getChildCount());
        assertEquals(activity.getString(R.string.cards_filter_all), chipText(chips, 0));
        // The topics come back from the DAO ordered by name, so the chips are too.
        assertEquals("DBMS", chipText(chips, 1));
        assertEquals("Operating Systems", chipText(chips, 2));
        assertTrue("nothing is filtered until something is chosen",
                ((Chip) chips.getChildAt(0)).isChecked());
    }

    @Test
    public void choosingATopicLeavesOnlyItsCards() throws Exception {
        cachePulledCard(1L, "What is a deadlock?");
        cacheCardUnder(2L, 2L, "What does ACID stand for?");
        MainActivity activity = openActivity();
        relayoutList(activity);

        chooseChip(activity, 1);

        RecyclerView list = activity.findViewById(R.id.cards_list);
        relayout(list);
        assertEquals(1, list.getAdapter().getItemCount());
        assertEquals("What does ACID stand for?", text(list, 0, R.id.card_front));
    }

    /**
     * The band is deliberately outside the filter. Candidates are not in the deck yet, and
     * unresolved work must not be hideable by a view control — a batch nobody has decided about
     * would otherwise be one chip away from being forgotten entirely.
     */
    @Test
    public void aGeneratedBatchSurvivesTheFilterItWasNotGeneratedUnder() throws Exception {
        cachePulledCard(1L, "What is a deadlock?");
        cacheCandidate();
        MainActivity activity = openActivity();
        relayoutList(activity);

        // DBMS, which holds no cards at all and did not generate the batch.
        chooseChip(activity, 1);

        RecyclerView list = activity.findViewById(R.id.cards_list);
        relayout(list);
        assertEquals("the header and its one candidate, and no saved cards",
                2, list.getAdapter().getItemCount());
        assertEquals("What is normalization?", text(list, 1, R.id.candidate_front));
    }

    @Test
    public void anEmptyTopicSaysWhichTopicIsEmpty() throws Exception {
        cachePulledCard(1L, "What is a deadlock?");
        MainActivity activity = openActivity();
        relayoutList(activity);

        chooseChip(activity, 1);

        assertEquals(View.VISIBLE, activity.findViewById(R.id.cards_empty).getVisibility());
        assertEquals(activity.getString(R.string.cards_empty_topic_title, "DBMS"),
                text(activity, R.id.cards_empty_title));
        assertEquals("an empty topic wants a card written, not a sync",
                activity.getString(R.string.cards_empty_topic_body),
                text(activity, R.id.cards_empty_body));
    }

    @Test
    public void anEmptyDeckAsksForSomethingElseEntirely() throws Exception {
        MainActivity activity = openActivity();
        relayoutList(activity);

        assertEquals(View.VISIBLE, activity.findViewById(R.id.cards_empty).getVisibility());
        assertEquals(activity.getString(R.string.cards_empty_title),
                text(activity, R.id.cards_empty_title));
        assertNotEquals("the two empties are answered by different actions",
                activity.getString(R.string.cards_empty_topic_body),
                text(activity, R.id.cards_empty_body));
    }

    /**
     * The drill-in from a stats row. The chip has to arrive already chosen, or the screen would
     * show a filtered list with nothing on it saying what the filter is.
     */
    @Test
    public void arrivingFromATopicRowSelectsThatChip() throws Exception {
        cachePulledCard(1L, "What is a deadlock?");
        cacheCardUnder(2L, 2L, "What does ACID stand for?");
        MainActivity activity = openActivity();

        BottomNavigationView bottomNav = activity.findViewById(R.id.bottom_nav);
        bottomNav.setSelectedItemId(R.id.statsFragment);
        settle();

        Bundle args = new Bundle();
        args.putLong(CardListFragment.ARG_TOPIC_ID, 2L);
        navController(activity).navigate(R.id.action_stats_to_cardList, args);
        settle();
        relayoutList(activity);

        ChipGroup chips = activity.findViewById(R.id.cards_filter);
        assertEquals("DBMS", chipText(chips, 1));
        assertTrue("the list is scoped, so the chip has to say so",
                ((Chip) chips.getChildAt(1)).isChecked());

        RecyclerView list = activity.findViewById(R.id.cards_list);
        assertEquals(1, list.getAdapter().getItemCount());
        assertEquals("What does ACID stand for?", text(list, 0, R.id.card_front));
    }

    // ---- fixtures -----------------------------------------------------------------------------

    /**
     * Taps a chip and lets the swap finish.
     *
     * <p>The list leaves before the filter changes and arrives after, so a test that only idled
     * the looper would read the old list back.
     */
    private void chooseChip(MainActivity activity, int index) throws InterruptedException {
        ChipGroup chips = activity.findViewById(R.id.cards_filter);
        chips.getChildAt(index).performClick();
        shadowOf(Looper.getMainLooper()).idleFor(SWAP);
        settle();
    }

    private static String chipText(ChipGroup chips, int index) {
        return ((Chip) chips.getChildAt(index)).getText().toString();
    }

    private static String text(MainActivity activity, int id) {
        return ((TextView) activity.findViewById(id)).getText().toString();
    }

    private void relayoutList(MainActivity activity) {
        relayout(activity.findViewById(R.id.cards_list));
    }

    private NavController navController(MainActivity activity) {
        NavHostFragment host = (NavHostFragment)
                activity.getSupportFragmentManager().findFragmentById(R.id.nav_host);
        return host.getNavController();
    }

    private void cacheCardUnder(long id, long topicId, String front) {
        CardEntity pulled = new CardEntity();
        pulled.id = id;
        pulled.serverId = id;
        pulled.topicId = topicId;
        pulled.front = front;
        pulled.back = "back";
        pulled.dueDate = LocalDate.now();
        db.cards().upsertAll(List.of(pulled));
    }

    private void cacheCandidate() {
        CandidateEntity candidate = new CandidateEntity();
        candidate.topicId = 1L;
        candidate.front = "What is normalization?";
        candidate.back = "One fact in one place.";
        candidate.generatedAt = Instant.parse("2026-01-01T00:00:00Z");
        db.candidates().insertAll(List.of(candidate));
    }
}
