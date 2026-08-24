package dev.vsdeadshot.flashcards.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.vsdeadshot.flashcards.ai.GeminiClient;
import dev.vsdeadshot.flashcards.ai.UnconfiguredGeminiClient;
import dev.vsdeadshot.flashcards.support.EmbeddedPostgresTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The application has to start and serve every other endpoint with generation switched off, which
 * is what this pins. Which variable switches it on, and which plausible-looking one does nothing,
 * is pinned by {@link GeminiPropertiesBindingTest}.
 *
 * <p><strong>The key it asserts is absent is cleared by {@link EmbeddedPostgresTest}, not by this
 * class.</strong> Without that, this asserted a fact about the shell the build was started from:
 * it passed on a machine that had never run generation and failed for anyone who had, with a
 * message blaming the suite for a variable the developer had set on purpose. Clearing it on the
 * base class rather than here is deliberate twice over — every context the suite starts is
 * covered rather than this one, and a class carrying inlined properties of its own would stop
 * sharing the cached context, paying a second context boot to protect a single assertion.
 */
@DisplayName("Gemini configuration")
class GeminiConfigurationTest extends EmbeddedPostgresTest {

    @Autowired
    private GeminiClient client;

    @Autowired
    private GeminiProperties properties;

    @Test
    @DisplayName("starts the application without a key instead of refusing to boot")
    void startsWithoutAKey() {
        // A precondition rather than the claim: it says the context under test really is in the
        // unconfigured state, so the assertion below is about what the container did and not about
        // what happened to be exported.
        assertFalse(properties.configured(),
                "a cleared key should read as unconfigured, whatever the environment holds");
        assertInstanceOf(UnconfiguredGeminiClient.class, client,
                "with no key the container should wire the stand-in, not fail");
    }

    @Test
    @DisplayName("defaults the model so a rename is a config change, not a code change")
    void defaultsTheModel() {
        assertEquals("gemini-3.6-flash", properties.model(),
                "an absent model property should still leave a usable default");
    }
}
