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
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import dev.vsdeadshot.flashcards.R;
import dev.vsdeadshot.flashcards.ui.Motion;

/**
 * Every card there is, grouped by topic, with the ones the server has not caught up on marked.
 *
 * <p>A row opens the editor; the button writes a new card. What this screen is really for
 * beyond that is noticing a card the server refused, which nothing else would surface.
 */
public final class CardListFragment extends Fragment {

    public CardListFragment() {
        super(R.layout.fragment_card_list);
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
        addGenerateAction();

        CardListViewModel model = new ViewModelProvider(this).get(CardListViewModel.class);
        CardListAdapter adapter = new CardListAdapter(this::openEditor, decisions(model));
        RecyclerView list = view.findViewById(R.id.cards_list);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(adapter);

        View empty = view.findViewById(R.id.cards_empty);
        view.findViewById(R.id.cards_new)
                .setOnClickListener(clicked -> openEditor(CardEditorViewModel.NEW_CARD));

        model.items().observe(getViewLifecycleOwner(), rows -> {
            adapter.submitList(rows);
            // Swapped rather than overlaid: an empty message sitting on top of a list that is
            // about to arrive reads as a failure for the moment before the rows land. A batch
            // with no saved cards behind it still counts as something to show.
            boolean nothingToShow = rows.isEmpty();
            empty.setVisibility(nothingToShow ? View.VISIBLE : View.GONE);
            list.setVisibility(nothingToShow ? View.GONE : View.VISIBLE);
        });
    }

    private CardListAdapter.OnCandidateDecision decisions(CardListViewModel model) {
        return new CardListAdapter.OnCandidateDecision() {
            @Override
            public void onOpen(long candidateId) {
                openCandidate(candidateId);
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
    private void openCandidate(long candidateId) {
        Bundle args = new Bundle();
        args.putLong(CardEditorFragment.ARG_CANDIDATE_ID, candidateId);
        args.putString(CardEditorFragment.ARG_TITLE, getString(R.string.editor_generated));
        NavHostFragment.findNavController(this)
                .navigate(R.id.action_cardList_to_cardEditor, args);
    }

    private void openEditor(long localId) {
        Bundle args = new Bundle();
        args.putLong(CardEditorFragment.ARG_CARD_ID, localId);
        // The destination's label is "{title}", which NavigationUI fills from the arguments — one
        // destination titled two ways, without a second destination or the Safe Args plugin.
        args.putString(CardEditorFragment.ARG_TITLE, getString(
                localId == CardEditorViewModel.NEW_CARD
                        ? R.string.editor_new
                        : R.string.editor_edit));
        NavHostFragment.findNavController(this)
                .navigate(R.id.action_cardList_to_cardEditor, args);
    }
}
