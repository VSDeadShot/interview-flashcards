package dev.vsdeadshot.flashcards.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class Sm2SchedulerTest {

    /** Ease factors are compared with a tolerance because they accumulate in floating point. */
    private static final double EPSILON = 1e-9;

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 28);

    private final Sm2Scheduler scheduler = new Sm2Scheduler();

    private static SchedulingState state(double easeFactor, int intervalDays, int repetitions) {
        return new SchedulingState(easeFactor, intervalDays, repetitions, 0, TODAY);
    }

    @Nested
    @DisplayName("golden vectors ported from DSA Tracker lib/sm2.test.ts")
    class PortedFromDsaTracker {

        @Test
        @DisplayName("1: a brand new card recalled perfectly waits one day and gains ease")
        void newCardGradeFive() {
            SchedulingState result = scheduler.schedule(SchedulingState.newCard(TODAY), 5, TODAY);

            assertEquals(1, result.intervalDays());
            assertEquals(2.6d, result.easeFactor(), EPSILON);
            assertEquals(1, result.repetitions());
            assertEquals(TODAY.plusDays(1), result.dueDate());
        }

        @Test
        @DisplayName("2: a lapse collapses the interval to one day but leaves ease untouched")
        void lapseResetsIntervalButNotEase() {
            SchedulingState result = scheduler.schedule(state(2.5d, 10, 4), 2, TODAY);

            assertEquals(1, result.intervalDays());
            assertEquals(2.5d, result.easeFactor(), EPSILON, "a single bad day must not degrade ease");
            assertEquals(0, result.repetitions());
            assertEquals(1, result.lapses());
        }

        @Test
        @DisplayName("3: the second successful review jumps to six days")
        void secondSuccessGoesToSixDays() {
            SchedulingState result = scheduler.schedule(state(2.5d, 1, 1), 4, TODAY);

            assertEquals(6, result.intervalDays());
            assertEquals(2.5d, result.easeFactor(), EPSILON, "confidence 4 is ease-neutral");
        }

        @Test
        @DisplayName("4: from the third review on, the interval is scaled by ease")
        void thirdSuccessScalesByEase() {
            SchedulingState result = scheduler.schedule(state(2.5d, 6, 2), 4, TODAY);

            assertEquals(15, result.intervalDays(), "round(6 * 2.5)");
            assertEquals(2.5d, result.easeFactor(), EPSILON);
        }

        @Test
        @DisplayName("5: ease never falls below the 1.3 floor")
        void easeFactorFloorHolds() {
            SchedulingState result = scheduler.schedule(state(Sm2Scheduler.MINIMUM_EASE_FACTOR, 5, 3), 3, TODAY);

            assertEquals(Sm2Scheduler.MINIMUM_EASE_FACTOR, result.easeFactor(), EPSILON);
        }
    }

    @Nested
    @DisplayName("the deliberate divergence from DSA Tracker")
    class LapseRecovery {

        @Test
        @DisplayName("6: recovery restarts at one day, where DSA Tracker would jump to six")
        void firstSuccessAfterLapseRestartsAtOneDay() {
            // Exactly the state a lapse leaves behind: interval 1, repetitions 0.
            SchedulingState afterLapse = scheduler.schedule(state(2.5d, 10, 4), 2, TODAY);
            assertEquals(1, afterLapse.intervalDays());
            assertEquals(0, afterLapse.repetitions());

            SchedulingState recovered = scheduler.schedule(afterLapse, 4, TODAY);

            assertEquals(1, recovered.intervalDays(),
                    "DSA Tracker reads interval==1 and returns 6; tracking repetitions gives 1");
            assertEquals(1, recovered.repetitions());
        }

        @Test
        @DisplayName("7: the step after that reaches six days, rejoining the normal ladder")
        void secondSuccessAfterLapseReachesSixDays() {
            SchedulingState result = scheduler.schedule(state(2.5d, 1, 1), 4, TODAY);

            assertEquals(6, result.intervalDays());
            assertEquals(2, result.repetitions());
        }

        @Test
        @DisplayName("a lapse mid-ladder costs the full climb, not one day")
        void lapseCostsTheWholeLadder() {
            SchedulingState mature = state(2.5d, 30, 5);

            SchedulingState day1 = scheduler.schedule(mature, 1, TODAY);
            SchedulingState day2 = scheduler.schedule(day1, 5, TODAY);
            SchedulingState day3 = scheduler.schedule(day2, 5, TODAY);

            assertEquals(1, day1.intervalDays());
            assertEquals(1, day2.intervalDays());
            assertEquals(6, day3.intervalDays());
            assertEquals(1, day3.lapses(), "the lapse is counted once, not on every later review");
        }
    }

    @Nested
    @DisplayName("ease factor arithmetic")
    class EaseFactor {

        @Test
        @DisplayName("confidence 5 adds 0.10")
        void perfectRecallAddsEase() {
            assertEquals(2.6d, scheduler.schedule(state(2.5d, 1, 1), 5, TODAY).easeFactor(), EPSILON);
        }

        @Test
        @DisplayName("confidence 4 is exactly neutral")
        void confidenceFourIsNeutral() {
            assertEquals(2.5d, scheduler.schedule(state(2.5d, 1, 1), 4, TODAY).easeFactor(), EPSILON);
        }

        @Test
        @DisplayName("confidence 3 subtracts 0.14")
        void hesitantRecallLosesEase() {
            assertEquals(2.36d, scheduler.schedule(state(2.5d, 1, 1), 3, TODAY).easeFactor(), EPSILON);
        }
    }

    @Nested
    @DisplayName("input validation")
    class Validation {

        @ParameterizedTest
        @ValueSource(ints = {0, -1, 6, 100})
        @DisplayName("confidence outside 1-5 is rejected")
        void rejectsOutOfRangeConfidence(int confidence) {
            assertThrows(IllegalArgumentException.class,
                    () -> scheduler.schedule(state(2.5d, 1, 1), confidence, TODAY));
        }

        @Test
        @DisplayName("an ease factor below the floor cannot be constructed")
        void rejectsEaseBelowFloor() {
            assertThrows(IllegalArgumentException.class, () -> state(1.2d, 1, 1));
        }
    }

    @Test
    @DisplayName("scheduling never mutates the state it was given")
    void inputStateIsNotMutated() {
        SchedulingState before = state(2.5d, 6, 2);

        scheduler.schedule(before, 5, TODAY);

        assertEquals(2.5d, before.easeFactor(), EPSILON);
        assertEquals(6, before.intervalDays());
        assertEquals(2, before.repetitions());
    }
}
