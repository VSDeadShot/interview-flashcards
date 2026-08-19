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
 * The suite runs with no FLASHCARDS_GEMINI_API_KEY set, which is the whole point: the application
 * has to start and serve every other endpoint without one. Which variable that is, and which
 * plausible-looking one does nothing, is pinned by {@link GeminiPropertiesBindingTest}.
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
        assertFalse(properties.configured(),
                "the suite must not depend on a key being present on the developer's machine");
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
