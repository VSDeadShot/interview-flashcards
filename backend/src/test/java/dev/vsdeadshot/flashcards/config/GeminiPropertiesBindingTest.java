package dev.vsdeadshot.flashcards.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * Which environment variable actually turns generation on.
 *
 * <p>This exists because the answer was written down wrong and nothing caught it. The key binds
 * to {@code flashcards.gemini.api-key}, so relaxed binding wants
 * {@code FLASHCARDS_GEMINI_API_KEY} — the whole prefix, not just the tail. Both the javadoc and
 * {@code application.properties} said {@code GEMINI_API_KEY}, reasoning by analogy with
 * {@code FLASHCARDS_API_KEY}; the analogy fails because that property is one level shallower.
 *
 * <p>{@code GeminiConfigurationTest} could not have caught it: with the wrong name the key simply
 * does not bind, which is the unconfigured state that test already asserts. Only naming the
 * variable and checking something happened distinguishes them.
 *
 * <p>Binds through a real {@link SystemEnvironmentPropertySource} rather than plain properties,
 * because the underscore-to-dash mapping is that source's behaviour and a property-name test
 * would prove nothing about it.
 */
@DisplayName("Binding the Gemini key from the environment")
class GeminiPropertiesBindingTest {

    private static GeminiProperties bindEnvironment(String name, String value) {
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(new SystemEnvironmentPropertySource(
                "systemEnvironment", Map.of(name, (Object) value)));
        // The model is otherwise absent here, and the record defaults it; this keeps the test
        // about the key alone.
        sources.addLast(new MapPropertySource("defaults", Map.of()));
        return Binder.get(new org.springframework.core.env.StandardEnvironment() {
            @Override
            public MutablePropertySources getPropertySources() {
                return sources;
            }
        }).bind("flashcards.gemini", GeminiProperties.class).orElse(
                new GeminiProperties(null, null));
    }

    @Test
    @DisplayName("FLASHCARDS_GEMINI_API_KEY is the name that switches generation on")
    void bindsFromTheFullyPrefixedName() {
        assertTrue(bindEnvironment("FLASHCARDS_GEMINI_API_KEY", "a-key").configured(),
                "this is the name the README and the properties file have to tell people");
    }

    @Test
    @DisplayName("GEMINI_API_KEY does not, however reasonable it looks")
    void doesNotBindFromTheUnprefixedName() {
        assertFalse(bindEnvironment("GEMINI_API_KEY", "a-key").configured(),
                "the prefix is flashcards.gemini, so the tail alone reaches nothing - setting"
                        + " this one leaves generation off with no error to say why");
    }
}
