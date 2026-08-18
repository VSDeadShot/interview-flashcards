package dev.vsdeadshot.flashcards.ai;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("With no Gemini key configured")
class UnconfiguredGeminiClientTest {

    private final GeminiClient client = new UnconfiguredGeminiClient();

    @Test
    @DisplayName("generating reports the feature as unavailable rather than failing obscurely")
    void reportsUnavailable() {
        assertThrows(GenerationUnavailableException.class,
                () -> client.generate(new GenerationPrompt("DBMS", null, List.of(), 8)),
                "a missing key must surface as the feature being off, not as a null dereference");
    }

    @Test
    @DisplayName("says the feature is unconfigured rather than blaming the network")
    void saysWhatIsActuallyWrong() {
        GenerationUnavailableException thrown = assertThrows(
                GenerationUnavailableException.class,
                () -> client.generate(new GenerationPrompt("DBMS", null, List.of(), 8)));

        assertTrue(thrown.getMessage().contains("not configured"),
                "the operator reading this needs to know it is a setting, not an outage");
    }
}
