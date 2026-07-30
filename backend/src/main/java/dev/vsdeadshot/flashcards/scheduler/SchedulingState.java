package dev.vsdeadshot.flashcards.scheduler;

import java.time.LocalDate;
import java.util.Objects;

/**
 * The complete spaced-repetition state of a single card.
 *
 * <p>This is a value object: {@link Sm2Scheduler} takes one and returns a new one. It is
 * deliberately not a JPA entity — the persistence layer maps these fields onto {@code card}
 * columns, but the scheduling logic has no idea a database exists.
 *
 * @param easeFactor   how quickly intervals grow; never below {@link Sm2Scheduler#MINIMUM_EASE_FACTOR}
 * @param intervalDays days between the last review and {@code dueDate}
 * @param repetitions  consecutive successful reviews; reset to 0 by a lapse
 * @param lapses       lifetime count of failed reviews, for statistics only
 * @param dueDate      the day this card next becomes available to study
 */
public record SchedulingState(
        double easeFactor,
        int intervalDays,
        int repetitions,
        int lapses,
        LocalDate dueDate) {

    public SchedulingState {
        Objects.requireNonNull(dueDate, "dueDate must not be null");
        if (easeFactor < Sm2Scheduler.MINIMUM_EASE_FACTOR) {
            throw new IllegalArgumentException(
                    "easeFactor must be >= " + Sm2Scheduler.MINIMUM_EASE_FACTOR + ", was " + easeFactor);
        }
        if (intervalDays < 0) {
            throw new IllegalArgumentException("intervalDays must not be negative, was " + intervalDays);
        }
        if (repetitions < 0) {
            throw new IllegalArgumentException("repetitions must not be negative, was " + repetitions);
        }
        if (lapses < 0) {
            throw new IllegalArgumentException("lapses must not be negative, was " + lapses);
        }
    }

    /**
     * State for a card that has never been reviewed. It is due immediately, which is what makes
     * a freshly added card show up in today's queue.
     */
    public static SchedulingState newCard(LocalDate today) {
        return new SchedulingState(Sm2Scheduler.INITIAL_EASE_FACTOR, 0, 0, 0, today);
    }
}
