package dev.vsdeadshot.flashcards.support;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the application {@link Clock} so "today" is a date a test can assert on rather than
 * whatever day the suite happens to run. This is the reason the services take a {@code Clock}
 * instead of calling {@code LocalDate.now()}.
 *
 * <p>Pull it in with an explicit {@code @Import}, not as a nested {@code @TestConfiguration}:
 * Spring Boot scans for those only on the class it is bootstrapping, and every {@code @Nested}
 * class is bootstrapped separately, so inner tests would quietly build a context holding the
 * real system clock instead.
 *
 * <p>{@link #NOW} is deliberately never today. A fixed date that happens to match the day the
 * suite runs makes date assertions pass whether the clock is honoured or not — which is a
 * false green this project has already been bitten by once.
 *
 * <p>Sharing one configuration across test classes also means they share a Spring context
 * rather than each paying to start their own.
 */
@Configuration
public class FixedClockConfiguration {

    public static final Instant NOW = Instant.parse("2026-03-17T09:00:00Z");

    public static final LocalDate TODAY = LocalDate.ofInstant(NOW, ZoneOffset.UTC);

    @Bean
    @Primary
    public Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }
}
