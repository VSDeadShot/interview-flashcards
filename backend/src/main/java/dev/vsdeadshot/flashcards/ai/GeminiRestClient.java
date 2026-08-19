package dev.vsdeadshot.flashcards.ai;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Gemini's Interactions API.
 *
 * <p>The response schema travels with the request rather than being asked for in the prompt, so
 * malformed JSON is the API's problem to prevent and not this class's to parse around.
 *
 * <p>Its timeouts are set by {@code GeminiConfiguration}, deliberately shorter than the Android
 * client's, so this side gives up first: a server still working after its caller has gone is
 * doing billable work nobody will ever see.
 */
public class GeminiRestClient implements GeminiClient {

    private static final String URL =
            "https://generativelanguage.googleapis.com/v1beta/interactions";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient http;
    private final String model;

    /**
     * Takes the builder as given and only adds the credential.
     *
     * <p>Transport tuning — timeouts in particular — belongs to whoever assembles the builder, not
     * here. Setting a request factory on the way past would also silently replace the one a test
     * had installed, which is the difference between exercising this class and quietly calling the
     * real API from a unit test.
     */
    public GeminiRestClient(RestClient.Builder builder, String apiKey, String model) {
        this.http = builder.defaultHeader("x-goog-api-key", apiKey).build();
        this.model = model;
    }

    @Override
    public List<GeneratedCard> generate(GenerationPrompt prompt) {
        String body;
        try {
            body = http.post()
                    .uri(URL)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request(prompt))
                    .retrieve()
                    .body(String.class);
        } catch (HttpClientErrorException.TooManyRequests e) {
            // The one 4xx that is a bad moment rather than a bad request. Caught before the
            // block below, which would otherwise call a rate limit permanent.
            throw new GenerationUnavailableException("The card generator did not answer.");
        } catch (HttpClientErrorException e) {
            // Every other 4xx means this request was wrong, and nobody holding the phone can
            // make it right - a stale key, a model that no longer exists, a body this client
            // built badly. Verified against the live endpoint: an invalid key is answered
            // 400 INVALID_ARGUMENT, not 401, so keying this on 401/403 alone reported the most
            // likely misconfiguration there is as a temporary outage and invited retries for as
            // long as the key stayed wrong.
            throw new GenerationMisconfiguredException(
                    "The card generator rejected our request.");
        } catch (RestClientException e) {
            // Deliberately drops the cause's message: an upstream body can echo request content,
            // and this message reaches a log.
            throw new GenerationUnavailableException("The card generator did not answer.");
        }
        return parse(body);
    }

    private Map<String, Object> request(GenerationPrompt prompt) {
        return Map.of(
                "model", model,
                "input", instructions(prompt),
                "response_format", Map.of(
                        "type", "text",
                        "mime_type", "application/json",
                        "schema", schema()));
    }

    /** Lowercase type names: the Interactions API does not take the older uppercase ones. */
    private static Map<String, Object> schema() {
        Map<String, Object> card = Map.of(
                "type", "object",
                "properties", Map.of(
                        "front", Map.of("type", "string"),
                        "back", Map.of("type", "string")),
                "required", List.of("front", "back"));
        return Map.of(
                "type", "object",
                "properties", Map.of("cards", Map.of("type", "array", "items", card)),
                "required", List.of("cards"));
    }

    private static String instructions(GenerationPrompt prompt) {
        StringBuilder text = new StringBuilder()
                .append("Write ").append(prompt.count())
                .append(" flashcards for a software engineering interview candidate revising ")
                .append(prompt.topicName()).append(".");
        if (prompt.focus() != null && !prompt.focus().isBlank()) {
            text.append(" Focus narrowly on: ").append(prompt.focus()).append(".");
        }
        text.append(" The front is a question; the back is a complete but concise answer.");
        if (!prompt.avoid().isEmpty()) {
            text.append(" Do not repeat or paraphrase any of these existing questions: ");
            text.append(String.join(" | ", prompt.avoid()));
        }
        return text.toString();
    }

    /**
     * The generated text is not a top-level field. A response is an interaction resource holding a
     * timeline of steps, and the text lives in the content blocks of a {@code model_output} step —
     * {@code output_text} is a convenience the SDKs synthesise, and there is no SDK here.
     *
     * <p>The last such step wins, and its text blocks are joined, which is what that convenience
     * does. Anything unparseable is refused rather than reported as a temporary failure: the call
     * succeeded, so retrying it identically would return the same unusable answer.
     */
    private static List<GeneratedCard> parse(String body) {
        JsonNode cards;
        try {
            String text = modelOutput(JSON.readTree(body));
            cards = text.isBlank() ? null : JSON.readTree(text).path("cards");
        } catch (RuntimeException e) {
            throw new GenerationRefusedException("The card generator returned nothing usable.");
        }
        // Unreadable and empty are different, and the split matters. A response this class cannot
        // read is its own problem and is refused here. A well-formed but empty batch is a fact
        // about the answer, not a fault, and belongs to the service that decides what is usable.
        if (cards == null || !cards.isArray()) {
            throw new GenerationRefusedException("The card generator returned nothing usable.");
        }
        List<GeneratedCard> generated = new ArrayList<>();
        for (JsonNode card : cards) {
            generated.add(new GeneratedCard(
                    card.path("front").asString(null), card.path("back").asString(null)));
        }
        return generated;
    }

    private static String modelOutput(JsonNode interaction) {
        StringBuilder text = new StringBuilder();
        for (JsonNode step : interaction.path("steps")) {
            if (!"model_output".equals(step.path("type").asString())) {
                continue;
            }
            text.setLength(0);
            for (JsonNode content : step.path("content")) {
                if ("text".equals(content.path("type").asString())) {
                    text.append(content.path("text").asString(""));
                }
            }
        }
        return text.toString();
    }
}
