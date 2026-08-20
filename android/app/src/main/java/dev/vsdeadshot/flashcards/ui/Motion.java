package dev.vsdeadshot.flashcards.ui;

import android.annotation.SuppressLint;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;
import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.interpolator.view.animation.FastOutLinearInInterpolator;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;
import androidx.interpolator.view.animation.LinearOutSlowInInterpolator;

/**
 * The motion every screen shares: three curves, one press, and one entrance.
 *
 * <p>Deliberately not a catalogue of every duration in the app. A screen's own beats — the card
 * flip, the band collapsing, a bar filling — live on that screen, because a constant used once is
 * only harder to read for having been moved away from its caller. What is here is what more than
 * one screen uses, and moving any of it would make two screens disagree.
 *
 * <p>The three interpolators are the whole vocabulary: things arriving decelerate, things leaving
 * accelerate, and something that both starts and stops on screen does both. Naming them once is
 * what stops a fourth curve being invented for a transition that is not different.
 */
public final class Motion {

    /** Starts and ends on screen: a card turning over, a field folding away. */
    public static final Interpolator FAST_OUT_SLOW_IN = new FastOutSlowInInterpolator();

    /** Arriving. Fast at first, settling into place — never the curve for something leaving. */
    public static final Interpolator LINEAR_OUT_SLOW_IN = new LinearOutSlowInInterpolator();

    /** Leaving. Gathers speed and is gone; nothing has to watch it finish. */
    public static final Interpolator FAST_OUT_LINEAR_IN = new FastOutLinearInInterpolator();

    /** How far a rising element starts below where it lands. */
    private static final float RISE_DP = 16f;

    /** One rise, used by the stats blocks and by both empty states. */
    public static final long RISE_MS = 340L;

    /** The gap between one rising element and the next. */
    public static final long RISE_STAGGER_MS = 70L;

    /** The one element per empty state that lands rather than merely arriving. */
    public static final long POP_MS = 460L;

    private static final float POP_FROM = 0.86f;

    /**
     * Just past 1. Enough for the tile to look like it landed, not enough to bounce — the two
     * lines behind it decelerate plainly, so a larger overshoot here would be the only thing
     * anyone looked at.
     */
    private static final float POP_TENSION = 1.05f;

    /** What a pill-shaped control scales to under a finger. */
    public static final float PRESS_BUTTON = 0.955f;

    /**
     * What a bottom-bar tab scales to instead.
     *
     * <p>Smaller on purpose. A tab is a third of the screen wide, and the amount that reads as a
     * press on a pill reads as a lurch on something that size.
     */
    public static final float PRESS_TAB = 0.98f;

    private Motion() {
    }

    /**
     * Makes a view scale under the finger and spring back on release.
     *
     * <p>A spring rather than a duration, because the length of a press is not known when it
     * starts: a duration has to be guessed, and the guess is wrong for either a tap or a hold.
     * The spring is under-damped enough to feel physical and damped enough not to wobble.
     */
    public static void press(@NonNull View view) {
        press(view, PRESS_BUTTON);
    }

    // The listener never consumes: it returns false for every event, so the view's own click
    // handling is untouched and there is no path on which a click could be swallowed unreported.
    // Lint cannot see that from the lambda's return type alone.
    @SuppressLint("ClickableViewAccessibility")
    public static void press(@NonNull View view, float pressedScale) {
        SpringAnimation x = spring(view, DynamicAnimation.SCALE_X);
        SpringAnimation y = spring(view, DynamicAnimation.SCALE_Y);

        view.setOnTouchListener((touched, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    scaleTo(x, y, pressedScale);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    scaleTo(x, y, 1f);
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    /**
     * The shared entrance: up from {@link #RISE_DP} and in from nothing.
     *
     * <p>Callers stagger by passing {@code n * RISE_STAGGER_MS}. The view is put in its starting
     * state here rather than in the layout, so a screen that never animates never has to
     * remember to set the alpha back.
     */
    public static void rise(@NonNull View view, long delayMs) {
        view.setAlpha(0f);
        view.setTranslationY(dp(view, RISE_DP));
        view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(RISE_MS)
                .setStartDelay(delayMs)
                .setInterpolator(LINEAR_OUT_SLOW_IN)
                .start();
    }

    /** The one overshooting element of an empty state. Everything else on it {@link #rise}s. */
    public static void pop(@NonNull View view) {
        view.setAlpha(0f);
        view.setScaleX(POP_FROM);
        view.setScaleY(POP_FROM);
        view.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(POP_MS)
                .setStartDelay(0L)
                .setInterpolator(new OvershootInterpolator(POP_TENSION))
                .start();
    }

    /** Density-independent pixels, resolved against the view actually being animated. */
    public static float dp(@NonNull View view, float value) {
        return value * view.getResources().getDisplayMetrics().density;
    }

    private static void scaleTo(SpringAnimation x, SpringAnimation y, float scale) {
        x.getSpring().setFinalPosition(scale);
        y.getSpring().setFinalPosition(scale);
        x.start();
        y.start();
    }

    private static SpringAnimation spring(View view, DynamicAnimation.ViewProperty property) {
        SpringAnimation animation = new SpringAnimation(view, property);
        animation.setSpring(new SpringForce()
                .setDampingRatio(0.6f)
                .setStiffness(SpringForce.STIFFNESS_MEDIUM));
        return animation;
    }
}
