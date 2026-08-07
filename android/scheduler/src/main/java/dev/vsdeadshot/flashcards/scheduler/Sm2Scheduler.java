package dev.vsdeadshot.flashcards.scheduler;

import java.time.LocalDate;
import java.util.Objects;

/**
 * The SM-2 spaced-repetition algorithm.
 *
 * <p>Ported from the JavaScript implementation in DSA Tracker ({@code lib/sm2.ts}) with one
 * deliberate correction. That version derives the next interval from the previous interval
 * alone. Because a lapse sets the interval to 1, the next successful review sees
 * {@code previousInterval == 1} and returns 6 — so a card you just blanked on is treated
 * exactly like a card that just passed its first review, and the lapse costs a single day.
 *
 * <p>This implementation tracks {@link SchedulingState#repetitions()} explicitly and resets it
 * to zero on a lapse, so recovery runs 1 day → 6 days → ease-scaled, as the original SM-2
 * paper describes.
 *
 * <p>One deviation from the paper is kept on purpose, matching DSA Tracker: <b>a lapse does not
 * reduce the ease factor.</b> A single bad day should not permanently degrade a card's schedule.
 *
 * <p>This class is stateless and has no clock — {@code today} is a parameter, which is what makes
 * the golden-vector tests deterministic rather than dependent on when they run.
 */
public final class Sm2Scheduler {

    /** Ease factor assigned to a card that has never been reviewed. */
    public static final double INITIAL_EASE_FACTOR = 2.5d;

    /** Floor on the ease factor. Below this, intervals stop growing usefully. */
    public static final double MINIMUM_EASE_FACTOR = 1.3d;

    public static final int MIN_CONFIDENCE = 1;
    public static final int MAX_CONFIDENCE = 5;

    /** Confidence below this counts as a lapse. */
    public static final int LAPSE_THRESHOLD = 3;

    /**
     * Applies one review to a card's schedule.
     *
     * @param current    the card's state before this review
     * @param confidence how well it was recalled, {@value #MIN_CONFIDENCE}–{@value #MAX_CONFIDENCE}
     * @param today      the date the review happened
     * @return the card's state after this review; {@code current} is not modified
     * @throws IllegalArgumentException if {@code confidence} is out of range
     */
    public SchedulingState schedule(SchedulingState current, int confidence, LocalDate today) {
        Objects.requireNonNull(current, "current must not be null");
        Objects.requireNonNull(today, "today must not be null");
        if (confidence < MIN_CONFIDENCE || confidence > MAX_CONFIDENCE) {
            throw new IllegalArgumentException(
                    "confidence must be between " + MIN_CONFIDENCE + " and " + MAX_CONFIDENCE
                            + ", was " + confidence);
        }

        if (confidence < LAPSE_THRESHOLD) {
            // Back to the start of the ladder tomorrow. Ease factor is left alone on purpose.
            return new SchedulingState(
                    current.easeFactor(), 1, 0, current.lapses() + 1, today.plusDays(1));
        }

        int repetitions = current.repetitions() + 1;
        // The interval uses the ease factor from *before* this review, matching SM-2.
        int intervalDays = switch (repetitions) {
            case 1 -> 1;
            case 2 -> 6;
            default -> (int) Math.round(current.intervalDays() * current.easeFactor());
        };

        return new SchedulingState(
                nextEaseFactor(current.easeFactor(), confidence),
                intervalDays,
                repetitions,
                current.lapses(),
                today.plusDays(intervalDays));
    }

    private static double nextEaseFactor(double current, int confidence) {
        int shortfall = MAX_CONFIDENCE - confidence;
        double next = current + (0.1d - shortfall * (0.08d + shortfall * 0.02d));
        return Math.max(next, MINIMUM_EASE_FACTOR);
    }
}
