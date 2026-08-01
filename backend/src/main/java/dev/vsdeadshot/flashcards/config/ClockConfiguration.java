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
     * <p>{@code systemDefaultZone} makes "today" server-local, which is the timezone question
     * left open in {@code docs/api-contract.md}: fine for one user in one zone, and the thing
     * to revisit before there are several.
     */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
