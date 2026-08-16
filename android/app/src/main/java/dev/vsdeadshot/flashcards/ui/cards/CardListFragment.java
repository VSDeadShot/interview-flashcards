package dev.vsdeadshot.flashcards.ui.cards;

import android.os.Bundle;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import dev.vsdeadshot.flashcards.R;

/**
 * Every card there is, grouped by topic, with the ones the server has not caught up on marked.
 *
 * <p>Rows are not tappable yet — the editor is its own change. Until then this is somewhere to
 * see what you have and, more to the point, to notice a card the server refused.
 */
public final class CardListFragment extends Fragment {

    public CardListFragment() {
        super(R.layout.fragment_card_list);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        CardListAdapter adapter = new CardListAdapter();
        RecyclerView list = view.findViewById(R.id.cards_list);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(adapter);

        View empty = view.findViewById(R.id.cards_empty);

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
}
