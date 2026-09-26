package dev.vsdeadshot.flashcards.ai;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
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

    private static final Logger log = LoggerFactory.getLogger(GeminiRestClient.class);

    /** Enough of Gemini's message to name the fault, not enough to carry a prompt with it. */
    static final int MAX_LOGGED_MESSAGE = 300;

    private final RestClient http;
    private final String apiKey;
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
        this.apiKey = apiKey;
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
            log.warn("Gemini rate-limited the request: {}", describe(e));
            throw new GenerationUnavailableException("The card generator did not answer.");
        } catch (HttpClientErrorException e) {
            // Every other 4xx means this request was wrong, and nobody holding the phone can
            // make it right - a stale key, a model that no longer exists, a body this client
            // built badly. Verified against the live endpoint: an invalid key is answered
            // 400 INVALID_ARGUMENT, not 401, so keying this on 401/403 alone reported the most
            // likely misconfiguration there is as a temporary outage and invited retries for as
            // long as the key stayed wrong.
            //
            // ERROR, and the only place Gemini's own reason is recorded: this becomes a bodyless
            // 500, so without this line a wrong key and a retired model look identical.
            log.error("Gemini rejected the request: {}", describe(e));
            throw new GenerationMisconfiguredException(
                    "The card generator rejected our request.");
        } catch (HttpStatusCodeException e) {
            // A 5xx: the same answer to the caller as no answer at all, but worth its status.
            log.warn("Gemini failed: {}", describe(e));
            throw new GenerationUnavailableException("The card generator did not answer.");
        } catch (RestClientException e) {
            // Deliberately drops the cause's message: an upstream body can echo request content,
            // and this message reaches a log. The type alone says timeout versus refused.
            log.warn("Gemini did not answer: model={} cause={}", model,
                    e.getClass().getSimpleName());
            throw new GenerationUnavailableException("The card generator did not answer.");
        }
        return parse(body);
    }

    private String describe(HttpStatusCodeException e) {
        return failureSummary(
                e.getStatusCode().value(), model, e.getResponseBodyAsString(), apiKey);
    }

    /**
     * One log line naming why Gemini refused, built from named fields and never the raw body.
     *
     * <p>The body is not logged as-is for the reason the exception's message is dropped: it can
     * echo what was sent, and what was sent is the prompt. {@code error.message} is the one free
     * text field kept, so it is truncated, stripped of line breaks that would forge a second log
     * line, and scrubbed of the key on the chance an error ever quotes it back. An unreadable
     * body leaves the fields absent rather than falling back to printing it.
     *
     * <p>Reads both shapes: the live endpoint wraps its error object in a JSON array.
     */
    static String failureSummary(int status, String model, String body, String apiKey) {
        String geminiStatus = null;
        String reason = null;
        String message = null;
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode error = (root.isArray() ? root.path(0) : root).path("error");
            geminiStatus = string(error.path("status"));
            message = string(error.path("message"));
            for (JsonNode detail : error.path("details")) {
                reason = string(detail.path("reason"));
                if (reason != null) {
                    break;
                }
            }
        } catch (RuntimeException ignored) {
            // Nothing readable; the HTTP status and model still go out.
        }
        return "status=" + status + " model=" + model + " error.status=" + geminiStatus
                + " reason=" + reason + " message=" + scrub(message, apiKey);
    }

    private static String string(JsonNode node) {
        return node.isString() ? node.asString() : null;
    }

    private static String scrub(String message, String apiKey) {
        if (message == null) {
            return null;
        }
        String clean = message.replaceAll("\\p{Cntrl}", " ");
        if (apiKey != null && !apiKey.isBlank()) {
            clean = clean.replace(apiKey, "[redacted]");
        }
        return clean.length() <= MAX_LOGGED_MESSAGE
                ? clean
                : clean.substring(0, MAX_LOGGED_MESSAGE) + "...";
    }

    private Map<String, Object> request(GenerationPrompt prompt) {
        return Map.of(
                "model", model,
                "input", GenerationInstructions.build(prompt, GenerationInstructions.nonce()),
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
