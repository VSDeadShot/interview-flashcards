package dev.vsdeadshot.flashcards.ai;

import java.util.List;

/**
 * What gets wired when no API key is present.
 *
 * <p>The alternative — refusing to start, the way a missing {@code FLASHCARDS_API_KEY} does —
 * was rejected deliberately. That key is required because nothing works without it. Generation
 * is one capability, and making its key mandatory would stop the application booting without one
 * and force every test context to supply one, destroying the zero-setup property of
 * {@code ./gradlew test}.
 *
 * <p>Choosing the branch once, at wiring time, is also why no caller has to ask whether there is
 * a client before using one.
 */
public class UnconfiguredGeminiClient implements GeminiClient {

    @Override
    public List<GeneratedCard> generate(GenerationPrompt prompt) {
        throw new GenerationUnavailableException("Card generation is not configured.");
    }
}
