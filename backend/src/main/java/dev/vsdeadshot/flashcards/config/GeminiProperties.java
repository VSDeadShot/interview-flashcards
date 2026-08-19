package dev.vsdeadshot.flashcards.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for card generation, read from the environment.
 *
 * <p>Deliberately not {@code @Validated} with a {@code @NotBlank} key, unlike
 * {@link FlashcardsProperties}: an absent key means the feature is off, not that the application
 * is misconfigured. See {@code UnconfiguredGeminiClient} for why that distinction is worth having.
 *
 * @param apiKey the Gemini credential, or null when generation is not enabled. Binds from
 *               {@code FLASHCARDS_GEMINI_API_KEY} — the whole prefix, since the property is
 *               {@code flashcards.gemini.api-key}. Not {@code GEMINI_API_KEY}, which reaches
 *               nothing and leaves generation quietly off; {@code GeminiPropertiesBindingTest}
 *               pins both halves of that. Never declared in a properties file, for the same
 *               reason the shared API key is not.
 * @param model  which model to ask. Defaulted rather than required, so a model rename is a
 *               configuration change and not a code change.
 */
@ConfigurationProperties(prefix = "flashcards.gemini")
public record GeminiProperties(String apiKey, String model) {

    private static final String DEFAULT_MODEL = "gemini-3.7-flash";

    public GeminiProperties {
        if (model == null || model.isBlank()) {
            model = DEFAULT_MODEL;
        }
    }

    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
