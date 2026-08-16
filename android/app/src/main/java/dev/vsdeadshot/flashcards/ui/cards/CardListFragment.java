package dev.vsdeadshot.flashcards.ui.cards;

import android.os.Bundle;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import dev.vsdeadshot.flashcards.R;

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
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        CardListAdapter adapter = new CardListAdapter(this::openEditor);
        RecyclerView list = view.findViewById(R.id.cards_list);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(adapter);

        View empty = view.findViewById(R.id.cards_empty);
        view.findViewById(R.id.cards_new)
                .setOnClickListener(clicked -> openEditor(CardEditorViewModel.NEW_CARD));

        CardListViewModel model = new ViewModelProvider(this).get(CardListViewModel.class);
        model.cards().observe(getViewLifecycleOwner(), cards -> {
            adapter.submitList(cards);
            // Swapped rather than overlaid: an empty message sitting on top of a list that is
            // about to arrive reads as a failure for the moment before the rows land.
            boolean nothingToShow = cards.isEmpty();
            empty.setVisibility(nothingToShow ? View.VISIBLE : View.GONE);
            list.setVisibility(nothingToShow ? View.GONE : View.VISIBLE);
        });
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
