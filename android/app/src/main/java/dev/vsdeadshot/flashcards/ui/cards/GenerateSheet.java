package dev.vsdeadshot.flashcards.ui.cards;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import dev.vsdeadshot.flashcards.R;
import dev.vsdeadshot.flashcards.data.local.TopicEntity;
import java.util.List;

/**
 * Ask for a batch: a topic, an optional focus, and how many.
 *
 * <p>A sheet rather than a destination of its own. Generating is a detour from the card list
 * that ends back on it, and the results land in the list behind — so a screen in the back stack
 * would be one the user has to leave before they can see what they asked for.
 */
public final class GenerateSheet extends BottomSheetDialogFragment {

    public static final String TAG = "generate";

    /** The backend's own default. Agreeing on it means the common case is one tap. */
    private static final int DEFAULT_COUNT = 8;

    private GenerateViewModel model;
    private List<TopicEntity> topics = List.of();
    private int chosenTopic = 0;

    public static GenerateSheet newInstance() {
        return new GenerateSheet();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.sheet_generate, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        model = new ViewModelProvider(this).get(GenerateViewModel.class);

        MaterialAutoCompleteTextView topicField = view.findViewById(R.id.generate_topic);
        topicField.setOnItemClickListener((parent, clicked, position, id) -> chosenTopic = position);

        ((TextInputEditText) view.findViewById(R.id.generate_count))
                .setText(String.valueOf(DEFAULT_COUNT));

        view.findViewById(R.id.generate_go).setOnClickListener(clicked -> generate(view));

        model.topics().observe(getViewLifecycleOwner(), cached -> drawTopics(view, cached));
        model.state().observe(getViewLifecycleOwner(), state -> draw(view, state));
    }

    private void drawTopics(View view, List<TopicEntity> cached) {
        topics = cached;
        MaterialAutoCompleteTextView topicField = view.findViewById(R.id.generate_topic);
        String[] names = new String[topics.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = topics.get(i).name;
        }
        topicField.setSimpleItems(names);
        if (names.length > 0 && topicField.getText().length() == 0) {
            // Pre-selected rather than left blank: with one topic cached this is the whole form,
            // and a required field nobody filled in costs a tap on the only possible answer.
            chosenTopic = 0;
            topicField.setText(names[0], false);
        }
        // Nothing to generate for is the dead end the editor already names, with the same remedy.
        view.findViewById(R.id.generate_go).setEnabled(names.length > 0);
    }

    private void generate(View view) {
        if (topics.isEmpty()) {
            return;
        }
        String focus = text(view, R.id.generate_focus);
        model.generate(topics.get(Math.min(chosenTopic, topics.size() - 1)).id,
                focus.isBlank() ? null : focus, count(view));
    }

    /**
     * Whatever was typed, clamped to something sendable. The server clamps above its own maximum
     * too; this exists so a blank or unparseable field is a batch of eight rather than a 400.
     */
    private int count(View view) {
        try {
            return Math.max(1, Integer.parseInt(text(view, R.id.generate_count).trim()));
        } catch (NumberFormatException e) {
            return DEFAULT_COUNT;
        }
    }

    private void draw(View view, GenerateViewModel.GenerateState state) {
        LinearProgressIndicator progress = view.findViewById(R.id.generate_progress);
        MaterialButton go = view.findViewById(R.id.generate_go);

        progress.setVisibility(state.running() ? View.VISIBLE : View.GONE);
        go.setEnabled(!state.running() && !topics.isEmpty());
        if (state.running()) {
            view.findViewById(R.id.generate_error).setVisibility(View.GONE);
            return;
        }

        if (state.error() != null) {
            showError(state.error());
            return;
        }
        if (state.generated() != null) {
            // The batch is in the card list behind this sheet, so the sheet's job is over.
            Toast.makeText(requireContext(), getResources().getQuantityString(
                            R.plurals.generate_done, state.generated(), state.generated()),
                    Toast.LENGTH_SHORT).show();
            dismiss();
        }
    }

    /**
     * Shown in the sheet rather than as a snackbar over the list. Every one of these failures is
     * answered by doing something with the inputs above — wait and retry, narrow the focus, turn
     * the radio on — so those inputs have to still be on screen when the message arrives.
     */
    void showError(@StringRes int message) {
        View view = requireView();
        TextView error = view.findViewById(R.id.generate_error);
        error.setText(message);
        error.setVisibility(View.VISIBLE);
        view.findViewById(R.id.generate_progress).setVisibility(View.GONE);
        view.findViewById(R.id.generate_go).setEnabled(!topics.isEmpty());
    }

    private static String text(View view, int id) {
        return ((TextInputEditText) view.findViewById(id)).getText().toString();
    }
}
