package dev.vsdeadshot.flashcards.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vsdeadshot.flashcards.domain.LoginAttempt;
import dev.vsdeadshot.flashcards.repository.LoginAttemptRepository;
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
 * The cap on how often a passphrase may be guessed.
 *
 * <p>Uses the fixed clock, so a window boundary is a value these assertions can be written
 * against rather than whatever moment the suite happens to run at.
 */
@Transactional
@Import(FixedClockConfiguration.class)
@DisplayName("The sign-in rate limit")
class LoginRateLimitTest extends EmbeddedPostgresTest {

    private static final String SOURCE = "203.0.113.7";
    private static final String OTHER_SOURCE = "203.0.113.8";

    @Autowired
    private LoginRateLimit limit;

    @Autowired
    private LoginAttemptRepository attempts;

    @Autowired
    private Clock clock;

    private void failures(String source, int count) {
        for (int i = 0; i < count; i++) {
            limit.recordFailure(source);
        }
    }

    /** Backdated directly, since the clock is fixed and cannot be wound forward. */
    private void failureAt(String source, Instant when) {
        attempts.save(new LoginAttempt(source, when));
    }

    @Nested
    @DisplayName("per source")
    class PerSource {

        @Test
        @DisplayName("allows every attempt up to the limit")
        void allowsUpToTheLimit() {
            failures(SOURCE, LoginRateLimit.MAX_PER_SOURCE - 1);

            assertDoesNotThrow(() -> limit.check(SOURCE),
                    "the limit is a ceiling, so it must not refuse anything below it");
        }

        @Test
        @DisplayName("refuses the attempt after the limit is spent")
        void refusesPastTheLimit() {
            failures(SOURCE, LoginRateLimit.MAX_PER_SOURCE);

            LoginLimitExceededException refused = assertThrows(
                    LoginLimitExceededException.class, () -> limit.check(SOURCE));
            assertTrue(refused.getRetryAfterSeconds() > 0,
                    "a wait of zero would invite an immediate retry that is still refused");
        }

        @Test
        @DisplayName("does not count one address against another")
        void sourcesAreCountedSeparately() {
            failures(OTHER_SOURCE, LoginRateLimit.MAX_PER_SOURCE);

            assertDoesNotThrow(() -> limit.check(SOURCE),
                    "somebody else guessing must not lock the owner out of their own account");
        }

        @Test
        @DisplayName("records nothing for an attempt it refused")
        void refusingRecordsNothing() {
            failures(SOURCE, LoginRateLimit.MAX_PER_SOURCE);
            assertThrows(LoginLimitExceededException.class, () -> limit.check(SOURCE));

            assertEquals(LoginRateLimit.MAX_PER_SOURCE, attempts.count(),
                    "counting refusals would let a caller extend its own lockout by knocking, "
                            + "which turns a limit into a permanent denial");
        }
    }

    @Nested
    @DisplayName("the rolling window")
    class Window {

        @Test
        @DisplayName("ignores failures older than the window")
        void oldFailuresAgeOut() {
            for (int i = 0; i < LoginRateLimit.MAX_PER_SOURCE; i++) {
                failureAt(SOURCE, clock.instant().minus(LoginRateLimit.WINDOW).minusSeconds(1));
            }

            assertDoesNotThrow(() -> limit.check(SOURCE),
                    "a lockout has to end, or one bad afternoon is permanent");
        }

        @Test
        @DisplayName("counts a failure exactly at the window's edge as still inside it")
        void theEdgeIsInside() {
            // Off by one here and the limit leaks one attempt per window forever.
            for (int i = 0; i < LoginRateLimit.MAX_PER_SOURCE; i++) {
                failureAt(SOURCE, clock.instant().minus(LoginRateLimit.WINDOW));
            }

            assertThrows(LoginLimitExceededException.class, () -> limit.check(SOURCE),
                    "the boundary belongs to the window it closes");
        }

        @Test
        @DisplayName("waits only until the oldest failure ages out, not a fixed cooldown")
        void waitsForTheOldestToExpire() {
            Instant fiveMinutesAgo = clock.instant().minus(Duration.ofMinutes(5));
            for (int i = 0; i < LoginRateLimit.MAX_PER_SOURCE; i++) {
                failureAt(SOURCE, fiveMinutesAgo);
            }

            LoginLimitExceededException refused = assertThrows(
                    LoginLimitExceededException.class, () -> limit.check(SOURCE));

            // Fifteen minutes of window, five already served.
            assertEquals(Duration.ofMinutes(10).toSeconds(), refused.getRetryAfterSeconds(),
                    "the wait runs from the oldest attempt, so time already served counts");
        }
    }

    /**
     * The per-source count keys on an address a caller behind a proxy can influence. This is
     * what a caller varying that address per attempt still runs into, and the reason the limit
     * is not merely reassuring.
     */
    @Nested
    @DisplayName("the global backstop")
    class Backstop {

        @Test
        @DisplayName("refuses once enough failures have arrived from anywhere at all")
        void spreadingAcrossSourcesStillTrips() {
            for (int i = 0; i < LoginRateLimit.MAX_GLOBAL; i++) {
                // A different address every time, which defeats the per-source count entirely.
                limit.recordFailure("198.51.100." + (i % 256) + ":" + i);
            }

            assertThrows(LoginLimitExceededException.class,
                    () -> limit.check("a-source-that-has-never-failed"),
                    "a limit that only counted per source would offer assurance it does not "
                            + "actually provide");
        }

        @Test
        @DisplayName("leaves an ordinary sign-in alone below it")
        void doesNotTripInNormalUse() {
            for (int i = 0; i < LoginRateLimit.MAX_GLOBAL - 1; i++) {
                limit.recordFailure("198.51.100." + (i % 256) + ":" + i);
            }

            assertDoesNotThrow(() -> limit.check(SOURCE),
                    "the backstop is for an attack, so normal use must never reach it");
        }
    }
}
