package dev.vsdeadshot.flashcards.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfiguration {

    /**
     * The single place the application learns what day it is.
     *
     * <p>Services take a {@link Clock} rather than calling {@code LocalDate.now()} so that
     * "today" is injectable, which is what keeps a card's due date as deterministic in tests
     * as the scheduler already is. The scheduler itself stays clock-free — it receives the
     * date as an argument, and this is where that argument comes from.
     *
     * <p><strong>The zone is configured, not the host's.</strong> {@code systemDefaultZone}
     * makes the day boundary a property of wherever the process happens to be running, which
     * is indistinguishable from correct for as long as that is the same machine the user is
     * on. It stops being so the moment this is deployed: a container runs UTC, and three
     * separate things then quietly answer for a different day — which cards
     * {@code findStudyQueue} calls due, where the streak's days start and end, and when the
     * generation allowance resets. None of them fail. They just move, by however far the
     * user is from UTC, and a schedule that is silently off by a day is exactly the kind of
     * corruption nothing would ever report.
     *
     * <p>So the zone follows the person rather than the host. It is deliberately a plain
     * configured value rather than a per-user column: there is one user, and a setting stored
     * alongside an account is the change to make when there are several, not before.
     */
    @Bean
    public Clock clock(FlashcardsProperties properties) {
        return Clock.system(properties.timezone());
    }
}
