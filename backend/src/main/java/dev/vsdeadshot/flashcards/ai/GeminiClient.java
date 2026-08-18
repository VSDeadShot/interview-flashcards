package dev.vsdeadshot.flashcards.ai;

import java.util.List;

/** The only thing in the application that knows Gemini's wire format. */
public interface GeminiClient {

    List<GeneratedCard> generate(GenerationPrompt prompt);
}
