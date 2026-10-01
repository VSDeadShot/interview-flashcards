package dev.vsdeadshot.flashcards.ui.cards;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.textfield.TextInputEditText;
import dev.vsdeadshot.flashcards.R;
import dev.vsdeadshot.flashcards.ui.Motion;
import dev.vsdeadshot.flashcards.ui.cards.NewTopicViewModel.TopicState;

/**
 * Ask for a topic by name.
 *
 * <p>A sheet over the card list, for generate's reason: it is a detour that ends back on the list,
 * and the new topic lands in the chip row behind it. Opened from the toolbar's overflow, because a
 * topic is made a handful of times in the life of a deck.
 *
 * <p>Online only, and the one screen besides generation that says so when it fails: a topic is
 * cached under the id the server gives it, so there is nothing to keep here until it has.
 */
public final class NewTopicSheet extends BottomSheetDialogFragment {

    public static final String TAG = "new_topic";

    private NewTopicViewModel model;
    private boolean running;

    public static NewTopicSheet newInstance() {
        return new NewTopicSheet();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.sheet_new_topic, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        model = new ViewModelProvider(this).get(NewTopicViewModel.class);

        TextInputEditText name = view.findViewById(R.id.new_topic_name);
        View create = view.findViewById(R.id.new_topic_create);
        Motion.press(create);

        create.setOnClickListener(clicked -> submit(view));
        // The field is the only input, so the keyboard's done key is the button. It goes through
        // the same check, so it cannot send what the button would not.
        name.setOnEditorActionListener((field, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit(view);
                return true;
            }
            return false;
        });
        name.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                drawButton(view);
            }
        });

        model.state().observe(getViewLifecycleOwner(), state -> draw(view, state));
        drawButton(view);
    }

    private void submit(View view) {
        if (canSend(view)) {
            model.create(name(view));
        }
    }

    private void draw(View view, TopicState state) {
        running = state.running();
        view.findViewById(R.id.new_topic_progress)
                .setVisibility(running ? View.VISIBLE : View.GONE);
        drawButton(view);

        TextView error = view.findViewById(R.id.new_topic_error);
        if (state.error() == null) {
            error.setVisibility(View.GONE);
        } else {
            error.setText(state.error());
            error.setVisibility(View.VISIBLE);
        }

        if (state.created() != null) {
            // The topic is already in the chip row behind this sheet, so the sheet's job is over.
            Toast.makeText(requireContext(), getString(R.string.new_topic_done, state.created()),
                    Toast.LENGTH_SHORT).show();
            dismiss();
        }
    }

    /**
     * Offered only when there is something to send and nothing in flight. Whitespace alone is
     * nothing: the server would answer 400, and a request spent learning that is a request somebody
     * waited on for no reason.
     */
    private void drawButton(View view) {
        view.findViewById(R.id.new_topic_create).setEnabled(canSend(view));
    }

    private boolean canSend(View view) {
        return !running && !name(view).isBlank();
    }

    private static String name(View view) {
        CharSequence text = ((TextInputEditText) view.findViewById(R.id.new_topic_name)).getText();
        return text == null ? "" : text.toString();
    }
}
