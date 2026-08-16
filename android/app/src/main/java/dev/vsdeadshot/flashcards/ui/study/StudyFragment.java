package dev.vsdeadshot.flashcards.ui.study;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import dev.vsdeadshot.flashcards.R;
import dev.vsdeadshot.flashcards.data.StudyRepository.StudyView;
import dev.vsdeadshot.flashcards.ui.study.StudyViewModel.StudyState;

/** The study queue: one card at a time, turned over and answered. */
public final class StudyFragment extends Fragment {

    /**
     * The confidence each button sends, in the order they appear. The values are the scheduler's
     * own scale, not indices — {@code Sm2Scheduler} rejects anything outside 1 to 5, and a button
     * that quietly sent a 0 would be refused rather than misread.
     */
    private static final int[] CONFIDENCE_BUTTONS = {
        R.id.study_confidence_1,
        R.id.study_confidence_2,
        R.id.study_confidence_3,
        R.id.study_confidence_4,
        R.id.study_confidence_5
    };

    private StudyViewModel model;

    public StudyFragment() {
        super(R.layout.fragment_study);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        model = new ViewModelProvider(this).get(StudyViewModel.class);

        view.findViewById(R.id.study_show_answer).setOnClickListener(clicked -> model.reveal());
        for (int i = 0; i < CONFIDENCE_BUTTONS.length; i++) {
            int confidence = i + 1;
            view.findViewById(CONFIDENCE_BUTTONS[i])
                    .setOnClickListener(clicked -> model.answer(confidence));
        }

        model.state().observe(getViewLifecycleOwner(), state -> draw(view, state));
    }

    @Override
    public void onResume() {
        super.onResume();
        // The screen may have been away while a sync archived the card it was showing. Reloading
        // keeps a revealed answer up when the card has not changed, so this does not undo a
        // rotation mid-question.
        model.reload();
    }

    private void draw(@NonNull View view, @NonNull StudyState state) {
        StudyView study = state.view();
        boolean hasCard = study.hasCard();

        view.findViewById(R.id.study_card).setVisibility(hasCard ? View.VISIBLE : View.GONE);
        view.findViewById(R.id.study_show_answer)
                .setVisibility(hasCard && !state.revealed() ? View.VISIBLE : View.GONE);
        view.findViewById(R.id.study_answers)
                .setVisibility(hasCard && state.revealed() ? View.VISIBLE : View.GONE);

        TextView empty = view.findViewById(R.id.study_empty);
        empty.setVisibility(hasCard ? View.GONE : View.VISIBLE);
        if (!hasCard) {
            empty.setText(study.isEmptyCache()
                    ? R.string.study_no_cards
                    : R.string.study_caught_up);
        }

        TextView dueCount = view.findViewById(R.id.study_due_count);
        dueCount.setText(getResources().getQuantityString(
                R.plurals.study_due_today, study.dueCount(), study.dueCount()));

        TextView topic = view.findViewById(R.id.study_topic);
        // Hidden rather than blank when the cache does not hold the topic: an empty heading
        // leaves a gap that reads as something failing to load.
        topic.setVisibility(hasCard && study.topicName() != null ? View.VISIBLE : View.GONE);

        if (hasCard) {
            if (study.topicName() != null) {
                topic.setText(study.topicName());
            }
            ((TextView) view.findViewById(R.id.study_front)).setText(study.card().front);
            ((TextView) view.findViewById(R.id.study_back)).setText(study.card().back);
            int answerVisibility = state.revealed() ? View.VISIBLE : View.GONE;
            view.findViewById(R.id.study_divider).setVisibility(answerVisibility);
            view.findViewById(R.id.study_back).setVisibility(answerVisibility);
        }
    }
}
