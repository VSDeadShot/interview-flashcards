package dev.vsdeadshot.flashcards.ui.cards;

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.FragmentNavigator;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.transition.Hold;
import com.google.android.material.transition.MaterialSharedAxis;
import dev.vsdeadshot.flashcards.R;
import dev.vsdeadshot.flashcards.data.local.TopicEntity;
import dev.vsdeadshot.flashcards.ui.Motion;
import java.util.List;

/**
 * Every card there is, scoped to a topic or not, with the ones the server has not caught up on
 * marked.
 *
 * <p>A row opens the editor; the button writes a new card. What this screen is really for
 * beyond that is noticing a card the server refused, which nothing else would surface.
 */
public final class CardListFragment extends Fragment {

    /** Which topic to arrive scoped to. Absent, or zero, means all of them. */
    public static final String ARG_TOPIC_ID = "topicId";

    /**
     * The name the growing row and the editor are matched by.
     *
     * <p>One name rather than one per card. A container transform maps a single start view to a
     * single end view, and only ever one row is being opened.
     */
    static final String EDITOR_TRANSITION = "card_editor";

    /** The list leaving as the filter changes, and the new one arriving. */
    private static final long SWAP_OUT_MS = 130L;

    private static final long SWAP_IN_MS = 220L;

    private static final float SWAP_DP = 6f;

    /** True between the outgoing half of a filter change and the list that answers it. */
    private boolean swapping;

    public CardListFragment() {
        super(R.layout.fragment_card_list);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Set here rather than in onViewCreated: a fragment's transitions are read when
        // the transaction that shows it is executed, which is before its view exists.
        //
        // Arriving with a topic means this was a drill-in from a stats row rather than a tab
        // being tapped, and a drill-in is not a move between peers. Shared axis Z says "into",
        // which is what crossing from a count to the things it counted actually is.
        if (topicArgument() != CardListViewModel.ALL_TOPICS) {
            setEnterTransition(new MaterialSharedAxis(MaterialSharedAxis.Z, true));
            setReturnTransition(new MaterialSharedAxis(MaterialSharedAxis.Z, false));
        } else {
            Motion.peerDestination(this);
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        addGenerateAction();
        // Put back after the editor borrowed it. This runs again when the screen returns, so the
        // hold openWith installs is only ever in force for the one navigation that wants it.
        if (topicArgument() == CardListViewModel.ALL_TOPICS) {
            Motion.peerDestination(this);
        }

        CardListViewModel model = new ViewModelProvider(this).get(CardListViewModel.class);
        CardListAdapter adapter = new CardListAdapter(this::openEditor, decisions(model));
        RecyclerView list = view.findViewById(R.id.cards_list);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(adapter);

        View empty = view.findViewById(R.id.cards_empty);
        View newCard = view.findViewById(R.id.cards_new);
        Motion.press(newCard);
        newCard.setOnClickListener(
                clicked -> openEditor(CardEditorViewModel.NEW_CARD, clicked));

        // Only meaningful on the first pass, and harmless afterwards: filterBy ignores a value it
        // already holds, so returning from the editor does not re-run the read.
        model.filterBy(topicArgument());

        model.topics().observe(getViewLifecycleOwner(),
                topics -> drawChips(view, model, topics));

        model.items().observe(getViewLifecycleOwner(), rows -> {
            adapter.submitList(rows);
            // Swapped rather than overlaid: an empty message sitting on top of a list that is
            // about to arrive reads as a failure for the moment before the rows land. A batch
            // with no saved cards behind it still counts as something to show.
            boolean nothingToShow = rows.isEmpty();
            empty.setVisibility(nothingToShow ? View.VISIBLE : View.GONE);
            list.setVisibility(nothingToShow ? View.GONE : View.VISIBLE);
            drawEmptyCopy(view, model, nothingToShow);
            if (swapping) {
                swapping = false;
                arrive(nothingToShow ? empty : list);
            }
        });

        model.justAdded().observe(getViewLifecycleOwner(), localId -> {
            if (localId == null) {
                return;
            }
            model.consumeJustAdded();
            // Named for the next bind rather than animated here: the row does not exist yet, and
            // will not until the list rebuilt by the same write reaches the adapter.
            adapter.highlightOnce(localId);
        });
    }

    /**
     * Draws one chip per topic, plus the one that clears the filter.
     *
     * <p>Rebuilt outright rather than reconciled. The set changes only when a pull adds or
     * removes a topic, which is rare and is not something anyone is mid-gesture on, and a chip
     * group of five is cheaper to rebuild than to diff.
     */
    private void drawChips(
            @NonNull View view, @NonNull CardListViewModel model, List<TopicEntity> topics) {
        ChipGroup group = view.findViewById(R.id.cards_filter);
        group.removeAllViews();
        group.addView(chip(group, getString(R.string.cards_filter_all),
                CardListViewModel.ALL_TOPICS, model));
        for (TopicEntity topic : topics) {
            group.addView(chip(group, topic.name, topic.id, model));
        }
        // The copy names the topic, and the name only becomes available with the topics.
        drawEmptyCopy(view, model, view.findViewById(R.id.cards_empty).getVisibility()
                == View.VISIBLE);
    }

    private Chip chip(
            ChipGroup group, String label, long topicId, @NonNull CardListViewModel model) {
        Chip chip = (Chip) getLayoutInflater()
                .inflate(R.layout.item_topic_chip, group, false);
        chip.setText(label);
        chip.setChecked(model.filter() == topicId);
        Motion.press(chip);
        chip.setOnClickListener(tapped -> choose(model, topicId));
        return chip;
    }

    /**
     * Changes the filter with the list off screen.
     *
     * <p>Out, swap, in. The set changes while nothing is showing, so no row is ever seen sliding
     * to a position it did not move to — which is what a list rebuilt in place looks like when
     * half of it has been removed from the middle.
     */
    private void choose(@NonNull CardListViewModel model, long topicId) {
        if (swapping || model.filter() == topicId) {
            return;
        }
        View view = requireView();
        View leaving = view.findViewById(R.id.cards_empty).getVisibility() == View.VISIBLE
                ? view.findViewById(R.id.cards_empty)
                : view.findViewById(R.id.cards_list);
        swapping = true;
        leaving.animate()
                .alpha(0f)
                .translationY(Motion.dp(leaving, SWAP_DP))
                .setDuration(SWAP_OUT_MS)
                .setInterpolator(Motion.FAST_OUT_LINEAR_IN)
                .withEndAction(() -> model.filterBy(topicId))
                .start();
    }

    private void arrive(@NonNull View arriving) {
        arriving.setAlpha(0f);
        arriving.setTranslationY(Motion.dp(arriving, SWAP_DP));
        arriving.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(SWAP_IN_MS)
                .setInterpolator(Motion.LINEAR_OUT_SLOW_IN)
                .start();
    }

    /**
     * Says which kind of empty this is.
     *
     * <p>An empty deck is a sync that has not run; an empty topic is a topic nothing has been
     * written for. Those are answered by different actions, so they are not one sentence with a
     * name substituted into it.
     */
    private void drawEmptyCopy(
            @NonNull View view, @NonNull CardListViewModel model, boolean showing) {
        if (!showing) {
            return;
        }
        String topic = model.filterName();
        ((android.widget.TextView) view.findViewById(R.id.cards_empty_title)).setText(
                topic == null
                        ? getString(R.string.cards_empty_title)
                        : getString(R.string.cards_empty_topic_title, topic));
        ((android.widget.TextView) view.findViewById(R.id.cards_empty_body)).setText(
                topic == null ? R.string.cards_empty_body : R.string.cards_empty_topic_body);

        Motion.pop(view.findViewById(R.id.cards_empty_tile));
        Motion.rise(view.findViewById(R.id.cards_empty_title), Motion.RISE_STAGGER_MS);
        Motion.rise(view.findViewById(R.id.cards_empty_body), Motion.RISE_STAGGER_MS * 2);
    }

    /**
     * The topic this screen was opened for, or {@link CardListViewModel#ALL_TOPICS}.
     *
     * <p>Read through {@code getArguments}, not {@code requireArguments}. The graph gives the
     * destination a bundle holding its declared defaults whichever way it is reached, but a
     * fragment restored before that bundle is attached would throw rather than read a zero, and
     * a zero is the right answer for every caller here.
     */
    private long topicArgument() {
        Bundle args = getArguments();
        return args == null
                ? CardListViewModel.ALL_TOPICS
                : args.getLong(ARG_TOPIC_ID, CardListViewModel.ALL_TOPICS);
    }

    private CardListAdapter.OnCandidateDecision decisions(CardListViewModel model) {
        return new CardListAdapter.OnCandidateDecision() {
            @Override
            public void onOpen(long candidateId, @NonNull View row) {
                openCandidate(candidateId, row);
            }

            @Override
            public void onAccept(long candidateId) {
                model.accept(candidateId);
            }

            @Override
            public void onDiscard(long candidateId) {
                model.discard(candidateId);
            }

            @Override
            public void onDiscardAll() {
                model.discardAll();
            }
        };
    }

    /**
     * Puts the generate action on the toolbar for as long as this screen is on it.
     *
     * <p>Not the floating action button, which means "new card" and should keep meaning
     * exactly one thing. The toolbar already hosts the sync action, so a verb up there
     * has precedent, and it leaves the FAB's meaning alone.
     *
     * <p>Scoped to STARTED so the item is added and removed with the screen rather than
     * lingering on the other two tabs, which have nothing to generate for.
     */
    private void addGenerateAction() {
        requireActivity().addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
                inflater.inflate(R.menu.cards_menu, menu);
            }

            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem item) {
                if (item.getItemId() != R.id.action_generate) {
                    return false;
                }
                GenerateSheet.newInstance()
                        .show(getParentFragmentManager(), GenerateSheet.TAG);
                return true;
            }
        }, getViewLifecycleOwner(), Lifecycle.State.STARTED);
    }

    /**
     * The same destination as a saved card, carrying the other id.
     *
     * <p>Titled a third way, which costs nothing: the destination's label is already filled from
     * its arguments, so "New card", "Edit card" and this share one screen and one back stack
     * entry rather than needing a fourth destination.
     */
    private void openCandidate(long candidateId, @NonNull View row) {
        Bundle args = new Bundle();
        args.putLong(CardEditorFragment.ARG_CANDIDATE_ID, candidateId);
        args.putString(CardEditorFragment.ARG_TITLE, getString(R.string.editor_generated));
        openWith(args, row);
    }

    private void openEditor(long localId, @NonNull View row) {
        Bundle args = new Bundle();
        args.putLong(CardEditorFragment.ARG_CARD_ID, localId);
        // The destination's label is "{title}", which NavigationUI fills from the arguments — one
        // destination titled two ways, without a second destination or the Safe Args plugin.
        args.putString(CardEditorFragment.ARG_TITLE, getString(
                localId == CardEditorViewModel.NEW_CARD
                        ? R.string.editor_new
                        : R.string.editor_edit));
        openWith(args, row);
    }

    /**
     * Grows the tapped thing into the editor rather than sliding a screen over it.
     *
     * <p>The exit transition is swapped for a hold first. Whatever is leaving must not fade while
     * the shared element is crossing, or the row being followed goes with it and there is nothing
     * left to watch. It is put back in {@link #onViewCreated}, which runs again when this screen
     * comes back, so the tab-to-tab fade is only ever suspended for the one navigation that
     * needs it.
     */
    private void openWith(@NonNull Bundle args, @NonNull View row) {
        setExitTransition(new Hold());
        row.setTransitionName(EDITOR_TRANSITION);
        FragmentNavigator.Extras extras = new FragmentNavigator.Extras.Builder()
                .addSharedElement(row, EDITOR_TRANSITION)
                .build();
        NavHostFragment.findNavController(this)
                .navigate(R.id.action_cardList_to_cardEditor, args, null, extras);
    }
}
