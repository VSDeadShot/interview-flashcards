package dev.vsdeadshot.flashcards.ui.study;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.transition.AutoTransition;
import androidx.transition.TransitionManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import dev.vsdeadshot.flashcards.R;
import dev.vsdeadshot.flashcards.data.StudyRepository.StudyView;
import dev.vsdeadshot.flashcards.ui.Motion;
import dev.vsdeadshot.flashcards.ui.study.StudyViewModel.StudyState;
import java.util.Objects;

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

    /** Each half of the flip. Two halves, so neither face is ever seen edge-on. */
    private static final long FLIP_HALF_MS = 210L;

    /**
     * How far the camera sits from the card, in device-independent pixels.
     *
     * <p>The platform default is about eight times the screen density, which puts the eye close
     * enough that a rotation swings half the card off the screen. The number is arbitrary; what
     * matters is that it is large, so the turn reads as a card and not as a door.
     */
    private static final float CAMERA_DISTANCE_DP = 8000f;

    /** The field collapsing as the flip begins, so its space is gone before the back lands. */
    private static final long FOLD_MS = 240L;

    /** Held back until the turn is past halfway, so the two are not competing. */
    private static final long WROTE_DELAY_MS = 180L;

    private static final long WROTE_MS = 220L;

    private static final float WROTE_RISE_DP = 8f;

    /** The chosen button lifting and taking its ring. */
    private static final long CHOSEN_MS = 140L;

    private static final float CHOSEN_LIFT_DP = 3f;

    private static final float CHOSEN_RING_DP = 3f;

    /** 30% of the accent, which is a ring rather than a second filled button. */
    private static final int CHOSEN_RING_ALPHA = 77;

    /**
     * How long the chosen answer stays lifted before the card leaves.
     *
     * <p>The point of the lift is that somebody sees which of the five they hit. A card that left
     * immediately would take that evidence with it, so this is the pause that makes the lift mean
     * anything.
     */
    private static final long CHOSEN_HOLD_MS = 340L;

    /**
     * The answered card leaving, upward, and the next one arriving from below.
     *
     * <p>Always those directions. The queue then reads as a stack being worked down rather than
     * as cards appearing from wherever the last animation happened to leave off.
     */
    private static final long CARD_OUT_MS = 200L;

    private static final float CARD_OUT_DP = -20f;

    private static final long CARD_IN_MS = 280L;

    private static final float CARD_IN_DP = 24f;

    /** The two lines of an empty state, rising behind the tile that lands. */
    private static final long EMPTY_TITLE_DELAY_MS = 130L;

    private static final long EMPTY_BODY_DELAY_MS = 200L;

    private static final String STATE_BOUND_CARD = "boundCardId";

    private StudyViewModel model;

    /**
     * Which card the views are currently drawn from, or null for none.
     *
     * <p>Saved across a configuration change on purpose. It is what decides whether an incoming
     * state is a new card — which clears the answer field and plays the handover — or the same
     * one arriving again, which has to leave both alone. Starting it empty after a rotation would
     * throw away whatever had been typed.
     */
    @Nullable
    private Long boundCardId;

    /** True from the moment a rating is pressed until the next card is bound. */
    private boolean answering;

    public StudyFragment() {
        super(R.layout.fragment_study);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Set here rather than in onViewCreated: a fragment's transitions are read when
        // the transaction that shows it is executed, which is before its view exists.
        Motion.peerDestination(this);
        if (savedInstanceState != null && savedInstanceState.containsKey(STATE_BOUND_CARD)) {
            boundCardId = savedInstanceState.getLong(STATE_BOUND_CARD);
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (boundCardId != null) {
            outState.putLong(STATE_BOUND_CARD, boundCardId);
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        model = new ViewModelProvider(this).get(StudyViewModel.class);

        MaterialButton showAnswer = view.findViewById(R.id.study_show_answer);
        Motion.press(showAnswer);
        showAnswer.setOnClickListener(clicked -> model.reveal());

        for (int i = 0; i < CONFIDENCE_BUTTONS.length; i++) {
            int confidence = i + 1;
            MaterialButton button = view.findViewById(CONFIDENCE_BUTTONS[i]);
            Motion.press(button);
            button.setOnClickListener(clicked -> choose(view, button, confidence));
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

    /**
     * Records the answer, after letting the choice be seen.
     *
     * <p>Three beats rather than one: the button lifts and takes a ring, the choice sits there
     * long enough to read, and only then does the card leave and the review get written.
     *
     * <p>The flag is what makes a second tap during any of that do nothing. Five buttons that all
     * still answered while the first was leaving would record whichever landed last, which is not
     * the one the user watched being chosen.
     */
    private void choose(@NonNull View view, @NonNull MaterialButton chosen, int confidence) {
        if (answering) {
            return;
        }
        answering = true;

        int accent = MaterialColors.getColor(
                chosen, androidx.appcompat.R.attr.colorPrimary);
        chosen.setStrokeColor(ColorStateList.valueOf(
                MaterialColors.compositeARGBWithAlpha(accent, CHOSEN_RING_ALPHA)));
        chosen.setStrokeWidth(Math.round(Motion.dp(chosen, CHOSEN_RING_DP)));
        chosen.animate()
                .translationZ(Motion.dp(chosen, CHOSEN_LIFT_DP))
                .setDuration(CHOSEN_MS)
                .setInterpolator(Motion.FAST_OUT_SLOW_IN)
                .start();

        View faces = view.findViewById(R.id.study_faces);
        faces.postDelayed(() -> {
            if (!isAdded()) {
                return;
            }
            faces.animate()
                    .alpha(0f)
                    .translationY(Motion.dp(faces, CARD_OUT_DP))
                    .setDuration(CARD_OUT_MS)
                    .setInterpolator(Motion.FAST_OUT_LINEAR_IN)
                    .withEndAction(() -> model.answer(confidence))
                    .start();
        }, CHOSEN_HOLD_MS);
    }

    private void draw(@NonNull View view, @NonNull StudyState state) {
        StudyView study = state.view();
        boolean hasCard = study.hasCard();
        Long incoming = hasCard ? study.card().id : null;
        boolean isNewCard = !Objects.equals(incoming, boundCardId);
        View back = view.findViewById(R.id.study_card_back);

        // The one moment the flip runs: this card was already on screen face up, and is now
        // revealed. A card that arrives already revealed - which a rotation produces - is drawn
        // turned rather than turning.
        boolean revealing = hasCard && state.revealed() && !isNewCard
                && back.getVisibility() != View.VISIBLE;
        if (revealing) {
            // Begun before the visibilities below change, because a delayed transition captures
            // the state it starts from. Started afterwards it would have nothing to animate and
            // the field would vanish between frames instead of folding.
            beginFold(view);
        }

        view.findViewById(R.id.study_header).setVisibility(hasCard ? View.VISIBLE : View.GONE);
        view.findViewById(R.id.study_faces).setVisibility(hasCard ? View.VISIBLE : View.GONE);
        view.findViewById(R.id.study_show_answer)
                .setVisibility(hasCard && !state.revealed() ? View.VISIBLE : View.GONE);
        view.findViewById(R.id.study_answers)
                .setVisibility(hasCard && state.revealed() ? View.VISIBLE : View.GONE);
        view.findViewById(R.id.study_attempt_block)
                .setVisibility(hasCard && !state.revealed() ? View.VISIBLE : View.GONE);

        drawEmpty(view, study, hasCard);
        if (hasCard) {
            drawCard(view, study, state, isNewCard, revealing);
        } else if (isNewCard) {
            // The queue ran out. Nothing will arrive to release the guard, so it is released
            // here - otherwise the screen would refuse every answer for the rest of the session.
            answering = false;
        }
        boundCardId = incoming;
    }

    private void drawEmpty(@NonNull View view, @NonNull StudyView study, boolean hasCard) {
        View empty = view.findViewById(R.id.study_empty);
        boolean wasShowing = empty.getVisibility() == View.VISIBLE;
        empty.setVisibility(hasCard ? View.GONE : View.VISIBLE);
        if (hasCard) {
            return;
        }

        TextView title = view.findViewById(R.id.study_empty_title);
        TextView body = view.findViewById(R.id.study_empty_body);
        title.setText(study.isEmptyCache()
                ? R.string.study_no_cards_title
                : R.string.study_caught_up_title);
        body.setText(study.isEmptyCache()
                ? R.string.study_no_cards_body
                : R.string.study_caught_up_body);

        // Only on the way in. A reload that finds the queue still empty must not replay the
        // entrance, or the screen twitches every time a sync lands behind it.
        if (!wasShowing) {
            Motion.pop(view.findViewById(R.id.study_empty_tile));
            Motion.rise(title, EMPTY_TITLE_DELAY_MS);
            Motion.rise(body, EMPTY_BODY_DELAY_MS);
        }
    }

    private void drawCard(@NonNull View view, @NonNull StudyView study, @NonNull StudyState state,
            boolean isNewCard, boolean revealing) {
        TextView topic = view.findViewById(R.id.study_topic);
        // Hidden rather than blank when the cache does not hold the topic: an empty heading
        // leaves a gap that reads as something failing to load.
        topic.setVisibility(study.topicName() != null ? View.VISIBLE : View.GONE);
        if (study.topicName() != null) {
            topic.setText(study.topicName());
        }

        ((TextView) view.findViewById(R.id.study_due_count)).setText(getResources()
                .getQuantityString(
                        R.plurals.study_due_today, study.dueCount(), study.dueCount()));

        // The question goes on both faces. Reading an answer without the question it belongs to
        // is the one thing a turned card can get wrong, and it costs two lines to prevent.
        ((TextView) view.findViewById(R.id.study_front)).setText(study.card().front);
        ((TextView) view.findViewById(R.id.study_back_prompt)).setText(study.card().front);
        ((TextView) view.findViewById(R.id.study_back)).setText(study.card().back);

        View front = view.findViewById(R.id.study_card);
        View back = view.findViewById(R.id.study_card_back);
        EditText attempt = view.findViewById(R.id.study_attempt);

        if (isNewCard) {
            arrive(view, front, back, attempt, state.revealed());
        } else if (revealing) {
            flip(front, back);
        }
        drawWrote(view, state.revealed(), attempt, revealing);
    }

    /**
     * Puts a card the right way up and slides it in from below.
     *
     * <p>Everything the last card left behind is reset here rather than where it was set: the
     * rotations, the lift and ring on whichever button was chosen, and the answer field. A card
     * that leaves mid-animation hands its angles to the next one otherwise, which is visible for
     * one card only and impossible to find afterwards.
     */
    private void arrive(@NonNull View view, @NonNull View front, @NonNull View back,
            @NonNull EditText attempt, boolean revealed) {
        front.setVisibility(revealed ? View.GONE : View.VISIBLE);
        front.setRotationY(0f);
        back.setVisibility(revealed ? View.VISIBLE : View.GONE);
        back.setRotationY(0f);
        attempt.setText("");

        for (int id : CONFIDENCE_BUTTONS) {
            MaterialButton button = view.findViewById(id);
            button.setStrokeWidth(0);
            button.setTranslationZ(0f);
        }

        View faces = view.findViewById(R.id.study_faces);
        faces.setAlpha(0f);
        faces.setTranslationY(Motion.dp(faces, CARD_IN_DP));
        faces.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(CARD_IN_MS)
                .setInterpolator(Motion.LINEAR_OUT_SLOW_IN)
                .withEndAction(() -> answering = false)
                .start();
    }

    /** The field going with the turn, so nothing has to shuffle up after the back face lands. */
    private void beginFold(@NonNull View view) {
        AutoTransition fold = new AutoTransition();
        fold.setDuration(FOLD_MS);
        fold.setInterpolator(Motion.FAST_OUT_SLOW_IN);
        TransitionManager.beginDelayedTransition((ViewGroup) view, fold);
    }

    private void flip(@NonNull View front, @NonNull View back) {
        float camera = Motion.dp(front, CAMERA_DISTANCE_DP);
        front.setCameraDistance(camera);
        back.setCameraDistance(camera);

        ObjectAnimator out = ObjectAnimator.ofFloat(front, View.ROTATION_Y, 0f, -90f);
        out.setDuration(FLIP_HALF_MS);
        out.setInterpolator(Motion.FAST_OUT_SLOW_IN);
        out.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                // Swapped edge-on, which is the one angle at which neither face is legible and
                // so the one angle at which an exchange cannot be seen happening.
                front.setVisibility(View.GONE);
                back.setVisibility(View.VISIBLE);
            }
        });

        ObjectAnimator in = ObjectAnimator.ofFloat(back, View.ROTATION_Y, 90f, 0f);
        in.setDuration(FLIP_HALF_MS);
        in.setInterpolator(Motion.FAST_OUT_SLOW_IN);

        AnimatorSet turn = new AnimatorSet();
        turn.playSequentially(out, in);
        turn.start();
    }

    /**
     * Shows what was typed, once there is an answer to hold it against.
     *
     * <p>Read back out of the field rather than kept anywhere. The field is hidden on reveal, not
     * cleared, so it is still the only copy — and being a view with an id it survives a rotation
     * on its own, which anywhere else this could live would have to be written to do.
     */
    private void drawWrote(@NonNull View view, boolean revealed, @NonNull EditText attempt,
            boolean animate) {
        String typed = attempt.getText().toString().trim();
        View block = view.findViewById(R.id.study_wrote_block);

        if (!revealed || typed.isEmpty()) {
            block.setVisibility(View.GONE);
            return;
        }

        ((TextView) view.findViewById(R.id.study_wrote)).setText(typed);
        block.setVisibility(View.VISIBLE);
        if (!animate) {
            block.setAlpha(1f);
            block.setTranslationY(0f);
            return;
        }
        block.setAlpha(0f);
        block.setTranslationY(Motion.dp(block, WROTE_RISE_DP));
        block.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(WROTE_DELAY_MS)
                .setDuration(WROTE_MS)
                .setInterpolator(Motion.LINEAR_OUT_SLOW_IN)
                .start();
    }
}
