package dev.vsdeadshot.flashcards.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * What the application refuses to start without.
 *
 * <p>No database and no web layer: this is about binding and validation, and booting Postgres to
 * ask whether a blank string is blank would say nothing extra. {@link ApplicationContextRunner}
 * builds a context holding just these properties.
 *
 * <p>The reason this class exists at all is that the passphrase hash <strong>became
 * required</strong> when the API key was removed. Before, an instance with no hash was a working
 * instance that simply could not issue tokens; now it is an instance that can serve nothing,
 * because signing in is the only way to obtain the only credential. Failing at startup, where
 * the message names the property, is much better than failing at somebody's first sign-in.
 */
@DisplayName("Application configuration")
class FlashcardsPropertiesTest {

    private static final String VALID_HASH =
            "$2a$04$ZKOq13fv4strw3LfpKGAxu.w4pN5lnbx2f/3vQS31jcHYCDsKgiLm";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(
                    org.springframework.boot.autoconfigure.AutoConfigurations.of(
                            ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(EnableProperties.class);

    @org.springframework.boot.context.properties.EnableConfigurationProperties(
            FlashcardsProperties.class)
    static class EnableProperties {
    }

    @Nested
    @DisplayName("with everything supplied")
    class Complete {

        @Test
        @DisplayName("starts and binds every value")
        void binds() {
            runner.withPropertyValues(
                            "flashcards.user-id=someone",
                            "flashcards.timezone=Asia/Kolkata",
                            "flashcards.passphrase-hash=" + VALID_HASH)
                    .run(context -> {
                        assertTrue(context.getStartupFailure() == null,
                                "a fully configured instance must start");
                        FlashcardsProperties properties =
                                context.getBean(FlashcardsProperties.class);
                        assertEquals("someone", properties.userId());
                        assertEquals(VALID_HASH, properties.passphraseHash());
                    });
        }
    }

    /**
     * The change this commit makes. An instance with no passphrase configured cannot admit
     * anybody at all now that the key is gone, so it must not pretend to be running.
     */
    @Nested
    @DisplayName("with no passphrase hash")
    class MissingPassphrase {

        @Test
        @DisplayName("refuses to start rather than running with no way in")
        void refusesToStart() {
            runner.withPropertyValues(
                            "flashcards.user-id=someone",
                            "flashcards.timezone=Asia/Kolkata")
                    .run(context -> assertTrue(context.getStartupFailure() != null,
                            "a missing passphrase hash has to fail startup, not surface as a "
                                    + "401 to whoever tries to sign in first"));
        }

        @Test
        @DisplayName("counts a blank one as missing")
        void blankIsMissing() {
            runner.withPropertyValues(
                            "flashcards.user-id=someone",
                            "flashcards.timezone=Asia/Kolkata",
                            "flashcards.passphrase-hash=   ")
                    .run(context -> assertTrue(context.getStartupFailure() != null,
                            "@NotBlank, not @NotNull — whitespace is not a configured hash"));
        }
    }

    /**
     * The key is gone, and nothing should be quietly reading it any more. Supplying it must not
     * be what makes an otherwise incomplete configuration start.
     */
    @Nested
    @DisplayName("with the retired API key still set")
    class RetiredKey {

        @Test
        @DisplayName("ignores it, and still refuses to start without a passphrase")
        void theKeyNoLongerCounts() {
            runner.withPropertyValues(
                            "flashcards.user-id=someone",
                            "flashcards.timezone=Asia/Kolkata",
                            "flashcards.api-key=the-old-shared-secret")
                    .run(context -> assertTrue(context.getStartupFailure() != null,
                            "an operator who removed the passphrase but left the key behind has "
                                    + "an instance nobody can reach, and should be told at boot"));
        }
    }

    @Nested
    @DisplayName("with an unusable timezone")
    class BadTimezone {

        @Test
        @DisplayName("fails at binding, where the property is named")
        void unknownZoneFailsStartup() {
            // Bound as a ZoneId rather than a String precisely so this is a startup failure and
            // not a surprise at the first request that asks what day it is.
            runner.withPropertyValues(
                            "flashcards.user-id=someone",
                            "flashcards.timezone=Mars/Olympus_Mons",
                            "flashcards.passphrase-hash=" + VALID_HASH)
                    .run(context -> assertTrue(context.getStartupFailure() != null,
                            "an unknown zone must not reach the first study queue"));
        }
    }
}
