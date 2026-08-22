package dev.vsdeadshot.flashcards.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.vsdeadshot.flashcards.support.EmbeddedPostgresTest;
import java.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * That the running application's clock is the configured one.
 *
 * <p>Deliberately asserts against the bound property rather than against a named zone. The
 * configured value is a deployment decision that may change; what must not change is that the
 * clock every service reads follows it. Pinning the literal would turn a one-line configuration
 * change into a failing test for no gain.
 *
 * <p>Binding itself needs no assertion here. {@code flashcards.timezone} is bound as a
 * {@code ZoneId}, so an unresolved placeholder or an unknown zone name fails conversion and the
 * context never starts — which every test in the suite would report, not just this one.
 */
@DisplayName("The configured timezone")
class ClockZoneTest extends EmbeddedPostgresTest {

    @Autowired
    private Clock clock;

    @Autowired
    private FlashcardsProperties properties;

    @Test
    @DisplayName("is the zone the application's clock runs in")
    void theClockRunsInTheConfiguredZone() {
        assertNotNull(properties.timezone(), "the zone is required, so binding must produce one");
        assertEquals(properties.timezone(), clock.getZone(),
                "every day boundary in the application is derived from this clock, so a clock "
                        + "ignoring the configured zone moves due dates, the streak and the "
                        + "generation reset together and silently");
    }
}
