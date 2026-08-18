package dev.vsdeadshot.flashcards.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@DisplayName("The Gemini REST client")
class GeminiRestClientTest {

    private static final String URL =
            "https://generativelanguage.googleapis.com/v1beta/interactions";

    /**
     * A response as the Interactions API actually shapes one: the generated text is not a
     * top-level field but a content block inside a model_output step. {@code output_text} is a
     * convenience the SDKs synthesise, and this client uses no SDK.
     */
    private static String interaction(String modelText) {
        return """
                {
                  "id": "int_123",
                  "object": "interaction",
                  "status": "completed",
                  "model": "gemini-3.7-flash",
                  "steps": [
                    {
                      "type": "model_output",
                      "content": [ { "type": "text", "text": "%s" } ]
                    }
                  ]
                }
                """.formatted(modelText.replace("\"", "\\\""));
    }

    private MockRestServiceServer server;
    private GeminiClient client;

    @BeforeEach
    void bind() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GeminiRestClient(builder, "test-key", "gemini-3.7-flash");
    }

    private static GenerationPrompt prompt() {
        return new GenerationPrompt("DBMS", "normalization", List.of("What is 3NF?"), 2);
    }

    @Nested
    @DisplayName("when the model answers")
    class Success {

        @Test
        @DisplayName("finds the cards inside the model_output step of the interaction")
        void readsCardsFromTheModelOutputStep() {
            server.expect(requestTo(URL))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(header("x-goog-api-key", "test-key"))
                    .andRespond(withSuccess(
                            interaction("{\"cards\":[{\"front\":\"Q1\",\"back\":\"A1\"},"
                                    + "{\"front\":\"Q2\",\"back\":\"A2\"}]}"),
                            MediaType.APPLICATION_JSON));

            List<GeneratedCard> cards = client.generate(prompt());

            assertEquals(2, cards.size(), "both objects should become cards");
            assertEquals("Q1", cards.get(0).front(), "the first front should survive parsing");
            assertEquals("A2", cards.get(1).back(), "the second back should survive parsing");
            server.verify();
        }

        @Test
        @DisplayName("constrains the model with a schema rather than asking for JSON in the prompt")
        void sendsTheResponseSchema() {
            server.expect(requestTo(URL))
                    .andExpect(jsonPath("$.response_format.mime_type").value("application/json"))
                    .andExpect(jsonPath("$.response_format.schema.type").value("object"))
                    .andExpect(jsonPath("$.response_format.schema.properties.cards.type")
                            .value("array"))
                    .andRespond(withSuccess(
                            interaction("{\"cards\":[]}"), MediaType.APPLICATION_JSON));

            client.generate(prompt());

            server.verify();
        }

        @Test
        @DisplayName("tells the model which questions the deck already covers")
        void sendsTheAvoidList() {
            server.expect(requestTo(URL))
                    .andExpect(jsonPath("$.input").value(
                            org.hamcrest.Matchers.containsString("What is 3NF?")))
                    .andRespond(withSuccess(
                            interaction("{\"cards\":[]}"), MediaType.APPLICATION_JSON));

            client.generate(prompt());

            server.verify();
        }
    }

    @Nested
    @DisplayName("when the call fails")
    class Failures {

        @Test
        @DisplayName("turns an upstream outage into a generation-unavailable failure")
        void upstreamServerErrorIsUnavailable() {
            server.expect(requestTo(URL)).andRespond(withServerError());

            assertThrows(GenerationUnavailableException.class, () -> client.generate(prompt()),
                    "an upstream outage should be reported as temporary");
        }

        @Test
        @DisplayName("turns a rate limit into a generation-unavailable failure")
        void rateLimitIsUnavailable() {
            server.expect(requestTo(URL))
                    .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                            .body("{\"error\":{\"code\":\"resource_exhausted\","
                                    + "\"message\":\"slow down\"}}")
                            .contentType(MediaType.APPLICATION_JSON));

            assertThrows(GenerationUnavailableException.class, () -> client.generate(prompt()),
                    "a rate limit is the same thing as an outage to the caller");
        }

        @Test
        @DisplayName("treats a rejected key as our misconfiguration, not a temporary outage")
        void rejectedKeyIsMisconfiguration() {
            server.expect(requestTo(URL))
                    .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                            .body("{\"error\":{\"code\":\"authentication\","
                                    + "\"message\":\"bad key\"}}")
                            .contentType(MediaType.APPLICATION_JSON));

            assertThrows(GenerationMisconfiguredException.class, () -> client.generate(prompt()),
                    "our credential being wrong is not something the caller can retry away");
        }

        @Test
        @DisplayName("refuses rather than retries when the answer cannot be read")
        void unreadableAnswerIsRefused() {
            server.expect(requestTo(URL))
                    .andRespond(withSuccess("{\"steps\":[]}", MediaType.APPLICATION_JSON));

            assertThrows(GenerationRefusedException.class, () -> client.generate(prompt()),
                    "the call succeeded, so an identical retry returns the same unusable answer");
        }

        @Test
        @DisplayName("never puts the API key in the failure message")
        void neverLeaksTheKey() {
            server.expect(requestTo(URL)).andRespond(withServerError());

            GenerationUnavailableException thrown = assertThrows(
                    GenerationUnavailableException.class, () -> client.generate(prompt()));

            assertFalse(String.valueOf(thrown.getMessage()).contains("test-key"),
                    "a failure message must never carry the credential");
            assertTrue(thrown.getMessage().startsWith("The card generator"),
                    "the message should describe the generator, not the transport");
        }
    }
}
