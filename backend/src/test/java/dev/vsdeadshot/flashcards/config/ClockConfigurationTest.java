package dev.vsdeadshot.flashcards.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Where the application's day boundary comes from.
 *
 * <p>No Spring context and no database: the bean method is a pure function of the properties
 * it is handed, and the thing worth pinning is which zone comes back rather than that the
 * container can build one. {@link ClockZoneTest} covers the wiring.
 */
@DisplayName("The application clock")
class ClockConfigurationTest {

    private static FlashcardsProperties propertiesFor(String zone) {
        return new FlashcardsProperties("key", "user", ZoneId.of(zone));
    }

    /**
     * Two zones rather than one, and that is the whole point of the test. A single assertion
     * would also pass under {@code systemDefaultZone()} on a machine that happens to be set to
     * the zone being asserted; no machine is set to two zones at once, so the pair is what
     * actually rules out a revert to the host's clock.
     */
    @Test
    @DisplayName("uses the configured zone rather than the host's")
    void usesTheConfiguredZone() {
        Clock kolkata = new ClockConfiguration().clock(propertiesFor("Asia/Kolkata"));
        Clock newYork = new ClockConfiguration().clock(propertiesFor("America/New_York"));

        assertEquals(ZoneId.of("Asia/Kolkata"), kolkata.getZone(),
                "the day boundary must follow the configured zone");
        assertEquals(ZoneId.of("America/New_York"), newYork.getZone(),
                "and must still follow it when it is a different one -- a clock that reads the "
                        + "host's zone would answer the same thing to both of these");
    }

    /**
     * UTC is the zone a deployed container reports, so it is the one that would be picked up
     * silently. It has to be reachable as a deliberate choice, not merely as an accident.
     */
    @Test
    @DisplayName("accepts UTC as a configured zone like any other")
    void acceptsUtc() {
        Clock clock = new ClockConfiguration().clock(propertiesFor("UTC"));

        assertEquals(ZoneId.of("UTC"), clock.getZone(), "UTC is a choice, not only a default");
    }
}
