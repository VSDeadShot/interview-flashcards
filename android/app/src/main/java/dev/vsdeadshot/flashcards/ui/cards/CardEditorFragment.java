package dev.vsdeadshot.flashcards.ui.cards;

import android.os.Bundle;
import android.view.View;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import dev.vsdeadshot.flashcards.R;
import dev.vsdeadshot.flashcards.data.local.CardEntity;
import dev.vsdeadshot.flashcards.data.local.TopicEntity;
import dev.vsdeadshot.flashcards.ui.cards.CardEditorViewModel.EditorState;
import dev.vsdeadshot.flashcards.ui.cards.CardEditorViewModel.Outcome;
import java.util.List;

/** Writing one card, or correcting one the server would not take. */
public final class CardEditorFragment extends Fragment {

    public static final String ARG_CARD_ID = "cardId";
    public static final String ARG_TITLE = "title";

    private CardEditorViewModel model;
    private List<TopicEntity> topics = List.of();
    private int chosenTopic = -1;

    public CardEditorFragment() {
        super(R.layout.fragment_card_editor);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        long cardId = requireArguments().getLong(ARG_CARD_ID, CardEditorViewModel.NEW_CARD);
        model = new ViewModelProvider(this, factoryFor(cardId)).get(CardEditorViewModel.class);

        MaterialAutoCompleteTextView topicField = view.findViewById(R.id.editor_topic);
        topicField.setOnItemClickListener((parent, clicked, position, id) -> chosenTopic = position);

        view.findViewById(R.id.editor_save).setOnClickListener(clicked -> save(view));
        view.findViewById(R.id.editor_archive).setOnClickListener(clicked -> confirmArchive());

        model.state().observe(getViewLifecycleOwner(), state -> draw(view, state));
        model.outcome().observe(getViewLifecycleOwner(), outcome -> {
            if (outcome == null) {
                return;
            }
            // Consumed before acting, so a rotation after saving does not navigate a second time
            // from a screen that has already left.
            model.consumeOutcome();
            if (outcome.kind() == Outcome.Kind.REFUSED) {
                Toast.makeText(requireContext(), outcome.message(), Toast.LENGTH_LONG).show();
            } else {
                NavHostFragment.findNavController(this).navigateUp();
            }
        });
    }

    private void draw(@NonNull View view, @NonNull EditorState state) {
        if (state.missing()) {
            // Archived on another device between the list being drawn and the row being tapped.
            Toast.makeText(requireContext(), R.string.editor_gone, Toast.LENGTH_LONG).show();
            NavHostFragment.findNavController(this).navigateUp();
            return;
        }

        topics = state.topics();
        view.findViewById(R.id.editor_fields)
                .setVisibility(state.canSave() ? View.VISIBLE : View.GONE);
        view.findViewById(R.id.editor_no_topics)
                .setVisibility(state.canSave() ? View.GONE : View.VISIBLE);
        if (!state.canSave()) {
            return;
        }

        MaterialAutoCompleteTextView topicField = view.findViewById(R.id.editor_topic);
        topicField.setSimpleItems(topicNames());

        CardEntity card = state.card();
        view.findViewById(R.id.editor_archive)
                .setVisibility(state.isNew() ? View.GONE : View.VISIBLE);

        if (chosenTopic < 0) {
            // Only on the first draw. Rewriting the fields on every state change would undo
            // whatever had been typed since.
            chosenTopic = card == null ? 0 : indexOfTopic(card.topicId);
            topicField.setText(topicNames()[chosenTopic], false);
            if (card != null) {
                ((TextInputEditText) view.findViewById(R.id.editor_front)).setText(card.front);
                ((TextInputEditText) view.findViewById(R.id.editor_back)).setText(card.back);
            }
        }

        View banner = view.findViewById(R.id.editor_rejected_banner);
        boolean refused = card != null && card.syncError != null;
        banner.setVisibility(refused ? View.VISIBLE : View.GONE);
        if (refused) {
            ((android.widget.TextView) view.findViewById(R.id.editor_rejected_text))
                    .setText(getString(R.string.editor_rejected, card.syncError));
        }
    }

    private void save(@NonNull View view) {
        TextInputLayout frontLayout = view.findViewById(R.id.editor_front_layout);
        TextInputLayout backLayout = view.findViewById(R.id.editor_back_layout);
        String front = textOf(view, R.id.editor_front);
        String back = textOf(view, R.id.editor_back);

        // Checked here as well as in the repository. This is the check that puts the message
        // beside the field somebody has to fix; the repository's is what makes the rule true
        // whichever screen is asking.
        frontLayout.setError(front.isBlank() ? getString(R.string.editor_required) : null);
        backLayout.setError(back.isBlank() ? getString(R.string.editor_required) : null);
        if (front.isBlank() || back.isBlank()) {
            return;
        }

        model.save(topics.get(Math.max(chosenTopic, 0)).id, front, back);
    }

    /**
     * Asks first, and says which of the two things archiving means here.
     *
     * <p>For a card the server has, it stops appearing and the history survives there. For a card
     * written on this device and never created, the row is the only copy that exists anywhere and
     * archiving deletes it — an asymmetry nothing on screen would otherwise show.
     */
    private void confirmArchive() {
        EditorState state = model.state().getValue();
        boolean neverReachedTheServer = state != null && state.card() != null
                && state.card().serverId == null;

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.editor_archive_title)
                .setMessage(neverReachedTheServer
                        ? R.string.editor_archive_local
                        : R.string.editor_archive_synced)
                .setNegativeButton(R.string.editor_cancel, null)
                .setPositiveButton(R.string.editor_archive, (dialog, button) -> model.archive())
                .show();
    }

    private String[] topicNames() {
        return topics.stream().map(topic -> topic.name).toArray(String[]::new);
    }

    private int indexOfTopic(long topicId) {
        for (int i = 0; i < topics.size(); i++) {
            if (topics.get(i).id == topicId) {
                return i;
            }
        }
        // The card's topic is not cached — deleted on the server, or not pulled yet. Saving has
        // to pick something the server will accept, and the first topic is as good as any.
        return 0;
    }

    private static String textOf(@NonNull View view, int id) {
        return ((TextInputEditText) view.findViewById(id)).getText().toString();
    }

    private ViewModelProvider.Factory factoryFor(long cardId) {
        return new ViewModelProvider.Factory() {
            @NonNull
            @Override
            @SuppressWarnings("unchecked")
            public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
                return (T) new CardEditorViewModel(requireActivity().getApplication(), cardId);
            }
        };
    }
}
