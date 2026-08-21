package dev.vsdeadshot.flashcards.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vsdeadshot.flashcards.domain.GenerationRequest;
import dev.vsdeadshot.flashcards.repository.GenerationRequestRepository;
import dev.vsdeadshot.flashcards.support.EmbeddedPostgresTest;
import dev.vsdeadshot.flashcards.support.FixedClockConfiguration;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/**
 * The cap on how much card generation one owner may ask for in a day.
 *
 * <p>Uses the fixed clock, so "the start of today" is a date these assertions can be written
 * against rather than whatever day the suite happens to run on.
 */
@Transactional
@Import(FixedClockConfiguration.class)
@DisplayName("The daily generation allowance")
class GenerationQuotaTest extends EmbeddedPostgresTest {

    private static final String USER = "quota-test";
    private static final String OTHER_USER = "somebody-else";

    @Autowired
    private GenerationQuota quota;

    @Autowired
    private GenerationRequestRepository requests;

    @Autowired
    private Clock clock;

    /** Spends the whole allowance, so the next call is the one on the boundary. */
    private void spendTheDay(String user) {
        for (int i = 0; i < GenerationQuota.MAX_PER_DAY; i++) {
            quota.consume(user, 8);
        }
    }

    @Nested
    @DisplayName("while there is allowance left")
    class WithinTheLimit {

        @Test
        @DisplayName("allows a generation and records it")
        void recordsAnAllowedGeneration() {
            quota.consume(USER, 8);

            assertEquals(1, requests.count(), "an allowed generation should leave a row behind");
            GenerationRequest recorded = requests.findAll().getFirst();
            assertEquals(USER, recorded.getUserId(), "the row belongs to whoever asked");
            assertEquals(8, recorded.getCardsRequested(), "and records what was asked for");
            assertEquals(clock.instant(), recorded.getCreatedAt(), "at the time it was asked");
        }

        @Test
        @DisplayName("allows every generation up to the limit")
        void allowsTheWholeAllowance() {
            assertDoesNotThrow(() -> spendTheDay(USER),
                    "the limit is a ceiling, so reaching it must not refuse anything below it");
            assertEquals(GenerationQuota.MAX_PER_DAY, requests.count(),
                    "every allowed call should be recorded");
        }
    }

    @Nested
    @DisplayName("once the allowance is spent")
    class AtTheLimit {

        @Test
        @DisplayName("refuses the next generation")
        void refusesTheNextOne() {
            spendTheDay(USER);

            GenerationLimitExceededException refused = assertThrows(
                    GenerationLimitExceededException.class, () -> quota.consume(USER, 8),
                    "the call after the allowance is spent is the one that must be refused");
            assertEquals(GenerationQuota.MAX_PER_DAY, refused.getLimit(),
                    "the refusal should name the limit it hit");
        }

        @Test
        @DisplayName("records nothing for a generation it refused")
        void refusingWritesNothing() {
            spendTheDay(USER);
            assertThrows(GenerationLimitExceededException.class, () -> quota.consume(USER, 8));

            assertEquals(GenerationQuota.MAX_PER_DAY, requests.count(),
                    "a refused call never reached the generator, so it must not be counted as "
                            + "one -- otherwise retrying pushes the reset further away");
        }

        @Test
        @DisplayName("says how long until the allowance comes back")
        void namesWhenToComeBack() {
            spendTheDay(USER);

            GenerationLimitExceededException refused = assertThrows(
                    GenerationLimitExceededException.class, () -> quota.consume(USER, 8));

            // The fixed clock stands at 09:00 UTC, so midnight is fifteen hours away.
            assertEquals(Duration.ofHours(15).toSeconds(), refused.getRetryAfterSeconds(),
                    "the wait should run to the next midnight, not a fixed cooldown");
            assertTrue(refused.getRetryAfterSeconds() > 0,
                    "a retry-after of zero would invite an immediate retry that is still refused");
        }
    }

    @Nested
    @DisplayName("counts only what belongs to today and to the caller")
    class Scope {

        /** Backdated directly, since the clock is fixed and cannot be wound forward. */
        private void recordAt(String user, Instant when) {
            requests.save(new GenerationRequest(user, 8, when));
        }

        @Test
        @DisplayName("ignores generations from yesterday")
        void yesterdayDoesNotCount() {
            for (int i = 0; i < GenerationQuota.MAX_PER_DAY; i++) {
                recordAt(USER, clock.instant().minus(Duration.ofDays(1)));
            }

            assertDoesNotThrow(() -> quota.consume(USER, 8),
                    "the allowance is daily, so a spent yesterday must not spend today");
        }

        @Test
        @DisplayName("ignores generations belonging to another owner")
        void anotherOwnerDoesNotCount() {
            spendTheDay(OTHER_USER);

            assertDoesNotThrow(() -> quota.consume(USER, 8),
                    "one owner exhausting their allowance must not spend anybody else's");
        }

        /**
         * The boundary the whole cap turns on. A row written at exactly midnight belongs to the
         * day that is starting, not the one that just ended — off by one here and the first
         * generation of the morning is charged to yesterday.
         */
        @Test
        @DisplayName("counts a generation made exactly at midnight against today")
        void midnightBelongsToToday() {
            Instant midnight = FixedClockConfiguration.TODAY
                    .atStartOfDay(clock.getZone()).toInstant();
            for (int i = 0; i < GenerationQuota.MAX_PER_DAY; i++) {
                recordAt(USER, midnight);
            }

            assertThrows(GenerationLimitExceededException.class, () -> quota.consume(USER, 8),
                    "midnight starts today, so those generations are today's");
        }

        @Test
        @DisplayName("does not count a generation made a moment before midnight")
        void justBeforeMidnightBelongsToYesterday() {
            Instant midnight = FixedClockConfiguration.TODAY
                    .atStartOfDay(clock.getZone()).toInstant();
            for (int i = 0; i < GenerationQuota.MAX_PER_DAY; i++) {
                recordAt(USER, midnight.minusMillis(1));
            }

            assertDoesNotThrow(() -> quota.consume(USER, 8),
                    "a millisecond before midnight is still yesterday's allowance");
        }
    }
}
