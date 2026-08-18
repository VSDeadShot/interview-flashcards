# AI-Assisted Card Generation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a user generate candidate flashcards for a topic from the Gemini API and review, edit and accept them before any card enters their deck.

**Architecture:** The backend gains one endpoint, `POST /api/v1/cards/generate`, which calls Gemini with a constrained response schema and returns candidates without storing anything. The client persists candidates in a new local Room table, shows them as a band inside the existing card list, and accepting one writes a card through the existing offline-authoring path so it rides the outbox like any hand-written card.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring `RestClient`, JUnit 5 + `MockRestServiceServer` (backend). Java 17 bytecode, Room 2.8.4, Retrofit 3 / OkHttp 5, JUnit 4 + Robolectric + MockWebServer (Android).

**Spec:** `docs/superpowers/specs/2026-08-18-ai-card-generation-design.md`

## Global Constraints

- **Java only, never Kotlin**, in both modules. No Jetpack Compose — the UI is XML views.
- Backend tests use plain JUnit `Assertions`. **AssertJ is not on the classpath.**
- Backend test names are full sentences via `@DisplayName`, grouped with `@Nested`.
- Android is **JUnit 4**; `@Nested`/`@DisplayName` are unavailable. Use descriptive method names.
- Every Robolectric test needs `@Config(application = Application.class)`.
- **Flyway owns the backend schema** (`ddl-auto=validate`). This feature adds **no backend migration** — nothing is stored server-side.
- Every backend repository finder takes `userId`. A row owned by someone else reads as **not found**, never forbidden.
- **`ai/` must not import `jakarta.persistence`, Spring MVC, or `web/dto`.** It is the Gemini translation layer and nothing else.
- Secrets never enter the repo. `GEMINI_API_KEY` is read from the environment.
- `count`: default **8**, max **10**, clamped above the max, **`400`** at zero or below.
- `focus`: max **200** characters. `front`/`back`: max **10,000** characters.
- Avoid-list cap: **50** most recently created fronts for the topic.
- Timeouts: backend upstream read **45 s**; client read for generation **60 s** (default stays 20 s).
- Commits are Conventional Commits with a **capitalised subject**, no attribution footer, and a body explaining reasoning.
- **Do not push. Commit only, and stop for review after each task.**

### Refinement from the spec

The spec sketched `CardGenerator` inside `ai/`. This plan puts it in `service/` instead, because it needs `TopicRepository` and `CardRepository`, and `ai/` is required to stay free of persistence. This mirrors the existing split where `scheduler/` is pure and `StudyService` orchestrates it.

---

### Task 1: The Gemini client and generation core

**Files:**
- Create: `backend/src/main/java/dev/vsdeadshot/flashcards/ai/GeneratedCard.java`
- Create: `backend/src/main/java/dev/vsdeadshot/flashcards/ai/GenerationPrompt.java`
- Create: `backend/src/main/java/dev/vsdeadshot/flashcards/ai/GeminiClient.java`
- Create: `backend/src/main/java/dev/vsdeadshot/flashcards/ai/GeminiRestClient.java`
- Create: `backend/src/main/java/dev/vsdeadshot/flashcards/ai/GenerationUnavailableException.java`
- Create: `backend/src/main/java/dev/vsdeadshot/flashcards/ai/GenerationRefusedException.java`
- Create: `backend/src/main/java/dev/vsdeadshot/flashcards/ai/GenerationMisconfiguredException.java`
- Test: `backend/src/test/java/dev/vsdeadshot/flashcards/ai/GeminiRestClientTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `record GeneratedCard(String front, String back)`
  - `record GenerationPrompt(String topicName, String focus, List<String> avoid, int count)`
  - `interface GeminiClient { List<GeneratedCard> generate(GenerationPrompt prompt); }`
  - `class GeminiRestClient implements GeminiClient` — constructor `GeminiRestClient(RestClient.Builder builder, String apiKey, String model)`
  - `class GenerationUnavailableException extends RuntimeException` — constructor `(String message)`
  - `class GenerationRefusedException extends RuntimeException` — constructor `(String message)`
  - `class GenerationMisconfiguredException extends RuntimeException` — constructor `(String message)`

- [ ] **Step 1: Write the failing test**

Create `GeminiRestClientTest.java`:

```java
package dev.vsdeadshot.flashcards.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.util.List;
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

    private MockRestServiceServer server;
    private GeminiClient client;

    private void bind() {
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
        @DisplayName("returns one card per object in the model's JSON output")
        void returnsOneCardPerObject() {
            bind();
            server.expect(requestTo(URL))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(header("x-goog-api-key", "test-key"))
                    .andRespond(withSuccess("""
                            {"output_text":
                             "{\\"cards\\":[{\\"front\\":\\"Q1\\",\\"back\\":\\"A1\\"},
                                            {\\"front\\":\\"Q2\\",\\"back\\":\\"A2\\"}]}"}
                            """, MediaType.APPLICATION_JSON));

            List<GeneratedCard> cards = client.generate(prompt());

            assertEquals(2, cards.size(), "both objects should become cards");
            assertEquals("Q1", cards.get(0).front(), "the first front should survive parsing");
            assertEquals("A2", cards.get(1).back(), "the second back should survive parsing");
            server.verify();
        }
    }

    @Nested
    @DisplayName("when the call fails")
    class Failures {

        @Test
        @DisplayName("turns an upstream 500 into a generation-unavailable failure")
        void upstreamServerErrorIsUnavailable() {
            bind();
            server.expect(requestTo(URL)).andRespond(withServerError());

            assertThrows(GenerationUnavailableException.class, () -> client.generate(prompt()),
                    "an upstream outage should be reported as temporary");
        }

        @Test
        @DisplayName("turns a rate limit into a generation-unavailable failure")
        void rateLimitIsUnavailable() {
            bind();
            server.expect(requestTo(URL))
                    .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                            .body("{\"error\":{\"code\":\"resource_exhausted\",\"message\":\"slow down\"}}")
                            .contentType(MediaType.APPLICATION_JSON));

            assertThrows(GenerationUnavailableException.class, () -> client.generate(prompt()),
                    "a rate limit is the same thing as an outage to the caller");
        }

        @Test
        @DisplayName("treats a rejected key as our misconfiguration, not a temporary outage")
        void rejectedKeyIsMisconfiguration() {
            bind();
            server.expect(requestTo(URL))
                    .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                            .body("{\"error\":{\"code\":\"authentication\",\"message\":\"bad key\"}}")
                            .contentType(MediaType.APPLICATION_JSON));

            assertThrows(GenerationMisconfiguredException.class, () -> client.generate(prompt()),
                    "our credential being wrong is not something the caller can retry away");
        }

        @Test
        @DisplayName("never puts the API key in the failure message")
        void neverLeaksTheKey() {
            bind();
            server.expect(requestTo(URL)).andRespond(withServerError());

            GenerationUnavailableException thrown = assertThrows(
                    GenerationUnavailableException.class, () -> client.generate(prompt()));

            assertTrue(thrown.getMessage() == null || !thrown.getMessage().contains("test-key"),
                    "a failure message must never carry the credential");
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./gradlew test --tests '*GeminiRestClientTest'`
Expected: FAIL — compilation error, `GeminiRestClient` does not exist.

- [ ] **Step 3: Write the value types and exceptions**

`GeneratedCard.java`:

```java
package dev.vsdeadshot.flashcards.ai;

/** One candidate as the model produced it. Not a card: no id, no schedule, nothing persisted. */
public record GeneratedCard(String front, String back) {
}
```

`GenerationPrompt.java`:

```java
package dev.vsdeadshot.flashcards.ai;

import java.util.List;

/**
 * Everything the model needs, already assembled. The avoid-list arrives as plain strings so this
 * package never has to know what a Card is.
 */
public record GenerationPrompt(String topicName, String focus, List<String> avoid, int count) {
}
```

`GeminiClient.java`:

```java
package dev.vsdeadshot.flashcards.ai;

import java.util.List;

/** The only thing in the application that knows Gemini's wire format. */
public interface GeminiClient {

    List<GeneratedCard> generate(GenerationPrompt prompt);
}
```

`GenerationUnavailableException.java`:

```java
package dev.vsdeadshot.flashcards.ai;

/**
 * The generator could not be reached or could not answer right now: a rate limit, an upstream
 * outage, a timeout, or no key configured. All of these mean the same thing to a caller — not
 * your fault, try again shortly — which is why they are one exception and not four.
 */
public class GenerationUnavailableException extends RuntimeException {

    public GenerationUnavailableException(String message) {
        super(message);
    }
}
```

`GenerationMisconfiguredException.java`:

```java
package dev.vsdeadshot.flashcards.ai;

/**
 * The generator rejected our credentials. Distinct from unavailable because it is not temporary
 * and not the caller's to fix: it becomes a 500, which is the honest answer for a fault on this
 * side of the wire that the caller could not have caused and cannot resolve.
 */
public class GenerationMisconfiguredException extends RuntimeException {

    public GenerationMisconfiguredException(String message) {
        super(message);
    }
}
```

`GenerationRefusedException.java`:

```java
package dev.vsdeadshot.flashcards.ai;

/**
 * The generator answered, but nothing usable came back. Deliberately distinct from
 * {@link GenerationUnavailableException}: retrying this identical request will produce the same
 * nothing, so telling the user to try again would be a lie.
 */
public class GenerationRefusedException extends RuntimeException {

    public GenerationRefusedException(String message) {
        super(message);
    }
}
```

- [ ] **Step 4: Write the client**

`GeminiRestClient.java`:

```java
package dev.vsdeadshot.flashcards.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Gemini's Interactions API. The response schema is sent with the request rather than asked for
 * in the prompt, so malformed JSON is the API's problem to prevent and not this class's to parse
 * around.
 *
 * <p>The read timeout is deliberately shorter than the Android client's, so this side gives up
 * first: a server still working after its caller has gone is doing billable work nobody will see.
 */
public class GeminiRestClient implements GeminiClient {

    private static final String URL =
            "https://generativelanguage.googleapis.com/v1beta/interactions";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient http;
    private final String model;

    public GeminiRestClient(RestClient.Builder builder, String apiKey, String model) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(45));
        this.http = builder
                .requestFactory(factory)
                .defaultHeader("x-goog-api-key", apiKey)
                .build();
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
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden e) {
            // Our credential, not the caller's. Nothing they can do and nothing to retry, so this
            // must not be dressed up as a temporary outage.
            throw new GenerationMisconfiguredException("The card generator rejected our credentials.");
        } catch (RestClientException e) {
            // Deliberately does not include the cause's message: an upstream body could echo
            // request content, and this message reaches a log.
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

    /** Lowercase JSON-Schema type names — the Interactions API does not use the older uppercase ones. */
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

    private static List<GeneratedCard> parse(String body) {
        try {
            JsonNode outputText = JSON.readTree(body).path("output_text");
            JsonNode cards = JSON.readTree(outputText.asText()).path("cards");
            List<GeneratedCard> generated = new ArrayList<>();
            for (JsonNode card : cards) {
                generated.add(new GeneratedCard(
                        card.path("front").asText(null), card.path("back").asText(null)));
            }
            return generated;
        } catch (Exception e) {
            throw new GenerationRefusedException("The card generator returned nothing usable.");
        }
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd backend && ./gradlew test --tests '*GeminiRestClientTest'`
Expected: PASS, 4 tests.

If the `output_text` field name proves wrong against a real response, fix `parse` and the test's stub body together — that pairing is the whole point of the test.

- [ ] **Step 6: Run the whole suite**

Run: `cd backend && ./gradlew test`
Expected: PASS, no regressions.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/dev/vsdeadshot/flashcards/ai backend/src/test/java/dev/vsdeadshot/flashcards/ai
git commit -F - <<'EOF'
feat(backend): Add the Gemini client

The one class that knows Gemini's wire format, behind a one-method
interface so everything above it can be tested without a network.

The response schema travels with the request rather than being asked
for in the prompt, which moves "is this valid JSON?" from runtime hope
into a request parameter. Type names are lowercase: the Interactions
API does not take the uppercase ones the older generateContent surface
used.

Two failure types, not one. Unavailable means a rate limit, an outage
or a timeout -- all of which mean try again shortly. Refused means the
model answered with nothing usable, where an identical retry will
produce the same nothing, so telling the user to retry would be a lie.

The failure message deliberately omits the upstream cause. An upstream
body can echo request content, and this message reaches a log.

The read timeout is 45s, under the Android client's 60s, so this side
gives up first -- a server still working after its caller has gone is
doing billable work nobody will ever see.
EOF
```


### Corrections applied during Task 1

Three things in the task above were wrong and were fixed while implementing it. Later tasks
assume the corrected versions:

- **Jackson is version 3, in the `tools.jackson` namespace**, not `com.fasterxml.jackson`. Boot 4
  ships `spring-boot-starter-jackson` with `tools.jackson.core:jackson-databind:3.1.4`, and
  Jackson 3 renamed `asText(...)` to `asString(...)`. Any later task parsing JSON on the backend
  must import `tools.jackson.databind`.
- **`output_text` is an SDK convenience, not a REST field.** A response is an interaction resource
  holding a timeline; the text lives at `steps[].content[].text` on a `model_output` step. The
  client walks that.
- **The client must not set a request factory.** `MockRestServiceServer.bindTo` installs its own,
  and overriding it makes every unit test call the real API. Transport tuning, including the 45 s
  read timeout, moves to `GeminiConfiguration` in Task 2.

Also: `Card`'s constructor takes `today` **before** `createdAt` —
`Card(userId, topic, front, back, today, createdAt, clientCardId)`. Task 3's fixture had them
swapped.

---

### Task 2: Configuration and the optional key

**Files:**
- Create: `backend/src/main/java/dev/vsdeadshot/flashcards/config/GeminiProperties.java`
- Create: `backend/src/main/java/dev/vsdeadshot/flashcards/config/GeminiConfiguration.java`
- Create: `backend/src/main/java/dev/vsdeadshot/flashcards/ai/UnconfiguredGeminiClient.java`
- Test: `backend/src/test/java/dev/vsdeadshot/flashcards/ai/UnconfiguredGeminiClientTest.java`
- Modify: `backend/src/main/resources/application.properties`

**Interfaces:**
- Consumes: `GeminiClient`, `GenerationPrompt`, `GenerationUnavailableException` (Task 1).
- Produces:
  - `record GeminiProperties(String apiKey, String model)` bound at prefix `flashcards.gemini`
  - `class GeminiConfiguration` exposing `@Bean GeminiClient geminiClient(RestClient.Builder, GeminiProperties)`
  - `class UnconfiguredGeminiClient implements GeminiClient`

- [ ] **Step 1: Write the failing test**

Create `UnconfiguredGeminiClientTest.java`:

```java
package dev.vsdeadshot.flashcards.ai;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("With no Gemini key configured")
class UnconfiguredGeminiClientTest {

    @Test
    @DisplayName("generating reports the feature as unavailable rather than failing obscurely")
    void reportsUnavailable() {
        GeminiClient client = new UnconfiguredGeminiClient();

        assertThrows(GenerationUnavailableException.class,
                () -> client.generate(new GenerationPrompt("DBMS", null, List.of(), 8)),
                "a missing key must surface as the feature being off, not as a null pointer");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./gradlew test --tests '*UnconfiguredGeminiClientTest'`
Expected: FAIL — `UnconfiguredGeminiClient` does not exist.

- [ ] **Step 3: Write the stand-in client**

```java
package dev.vsdeadshot.flashcards.ai;

import java.util.List;

/**
 * What gets wired when no API key is present.
 *
 * <p>The alternative — refusing to start — was rejected deliberately. FLASHCARDS_API_KEY is
 * required because nothing works without it; generation is one capability, and making its key
 * mandatory would stop the application booting without one and force every test context to
 * supply one, destroying the zero-setup property of {@code ./gradlew test}.
 */
public class UnconfiguredGeminiClient implements GeminiClient {

    @Override
    public List<GeneratedCard> generate(GenerationPrompt prompt) {
        throw new GenerationUnavailableException("Card generation is not configured.");
    }
}
```

- [ ] **Step 4: Write the properties and configuration**

`GeminiProperties.java`:

```java
package dev.vsdeadshot.flashcards.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * No {@code @NotBlank} on {@code apiKey}, unlike the shared API key: absent means the feature is
 * off, not that the application is misconfigured.
 */
@ConfigurationProperties(prefix = "flashcards.gemini")
public record GeminiProperties(String apiKey, String model) {

    public GeminiProperties {
        if (model == null || model.isBlank()) {
            model = "gemini-3.7-flash";
        }
    }

    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
```

`GeminiConfiguration.java`:

```java
package dev.vsdeadshot.flashcards.config;

import dev.vsdeadshot.flashcards.ai.GeminiClient;
import dev.vsdeadshot.flashcards.ai.GeminiRestClient;
import dev.vsdeadshot.flashcards.ai.UnconfiguredGeminiClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties(GeminiProperties.class)
public class GeminiConfiguration {

    /**
     * The absent-key branch is chosen here, once, rather than checked on every call. A caller
     * asking for a client should not also have to ask whether there is one.
     */
    @Bean
    public GeminiClient geminiClient(RestClient.Builder builder, GeminiProperties properties) {
        if (!properties.configured()) {
            return new UnconfiguredGeminiClient();
        }
        // Transport tuning lives here rather than in the client, so a test can bind a mock to a
        // plain builder without the client quietly replacing its request factory. 45s sits under
        // the Android client's 60s, so this side gives up first.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(45));
        return new GeminiRestClient(
                builder.requestFactory(factory), properties.apiKey(), properties.model());
    }
}
```

- [ ] **Step 5: Document the property in `application.properties`**

Append:

```properties
# The Gemini key binds from GEMINI_API_KEY by relaxed binding, exactly like the shared API key
# binds from FLASHCARDS_API_KEY, and is deliberately NOT declared here for the same reason:
# @ConfigurationProperties hands over unresolved placeholder text rather than failing, so
# writing flashcards.gemini.api-key=${GEMINI_API_KEY} would configure the literal string.
#
# Unlike the shared key it is optional. Absent, POST /cards/generate answers 503 and every other
# endpoint is unaffected -- generation is a capability, not a precondition, and requiring it
# would mean every test context needs a key.
flashcards.gemini.model=gemini-3.7-flash
```

- [ ] **Step 6: Run the tests**

Run: `cd backend && ./gradlew test`
Expected: PASS. `contextLoads()` proves the new beans wire with no `GEMINI_API_KEY` set.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/dev/vsdeadshot/flashcards/config backend/src/main/java/dev/vsdeadshot/flashcards/ai/UnconfiguredGeminiClient.java backend/src/test/java/dev/vsdeadshot/flashcards/ai/UnconfiguredGeminiClientTest.java backend/src/main/resources/application.properties
git commit -F - <<'EOF'
feat(backend): Wire the Gemini key as optional configuration

GEMINI_API_KEY binds from the environment the way FLASHCARDS_API_KEY
does, and is left out of application.properties for the same reason:
@ConfigurationProperties binding does not fail on a placeholder it
cannot resolve, it hands over the literal text.

It is optional where the shared key is required. Making it mandatory
would stop the application booting without a Gemini key and force
every test context to supply one, which would destroy the zero-setup
property of ./gradlew test. Generation is a capability, not a
precondition.

Absent, the container wires UnconfiguredGeminiClient, which reports the
feature as unavailable. Choosing the branch once at wiring time means
no caller has to ask whether there is a client before using one.
EOF
```

---

### Task 3: The endpoint, the service and the error mapping

**Files:**
- Create: `backend/src/main/java/dev/vsdeadshot/flashcards/service/CardGenerator.java`
- Create: `backend/src/main/java/dev/vsdeadshot/flashcards/web/dto/GenerateRequest.java`
- Create: `backend/src/main/java/dev/vsdeadshot/flashcards/web/dto/GenerateResponse.java`
- Create: `backend/src/main/java/dev/vsdeadshot/flashcards/web/dto/CandidateResponse.java`
- Modify: `backend/src/main/java/dev/vsdeadshot/flashcards/repository/CardRepository.java`
- Modify: `backend/src/main/java/dev/vsdeadshot/flashcards/web/CardController.java`
- Modify: `backend/src/main/java/dev/vsdeadshot/flashcards/web/ApiExceptionHandler.java`
- Modify: `docs/api-contract.md`
- Test: `backend/src/test/java/dev/vsdeadshot/flashcards/service/CardGeneratorTest.java`

**Interfaces:**
- Consumes: `GeminiClient`, `GeneratedCard`, `GenerationPrompt`, `GenerationUnavailableException`, `GenerationRefusedException` (Task 1).
- Produces:
  - `CardRepository.findRecentFronts(String userId, Long topicId, Limit limit)` returning `List<String>`
  - `CardGenerator.generate(String userId, long topicId, String focus, Integer count)` returning `List<GeneratedCard>`
  - `record GenerateRequest(Long topicId, String focus, Integer count)`
  - `record CandidateResponse(String front, String back)`
  - `record GenerateResponse(List<CandidateResponse> candidates)`

- [ ] **Step 1: Write the failing test**

Create `CardGeneratorTest.java`:

```java
package dev.vsdeadshot.flashcards.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vsdeadshot.flashcards.ai.GeneratedCard;
import dev.vsdeadshot.flashcards.ai.GeminiClient;
import dev.vsdeadshot.flashcards.ai.GenerationPrompt;
import dev.vsdeadshot.flashcards.ai.GenerationRefusedException;
import dev.vsdeadshot.flashcards.domain.Topic;
import dev.vsdeadshot.flashcards.repository.CardRepository;
import dev.vsdeadshot.flashcards.repository.TopicRepository;
import dev.vsdeadshot.flashcards.support.EmbeddedPostgresTest;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
@DisplayName("Generating cards for a topic")
class CardGeneratorTest extends EmbeddedPostgresTest {

    private static final String USER = "generator-test";

    @Autowired
    private TopicRepository topics;

    @Autowired
    private CardRepository cards;

    @Autowired
    private Clock clock;

    private Topic topic;

    /** Records the prompt it was handed so the test can assert on what the model was told. */
    private static final class RecordingClient implements GeminiClient {
        private final List<GeneratedCard> answer;
        private GenerationPrompt seen;

        RecordingClient(List<GeneratedCard> answer) {
            this.answer = answer;
        }

        @Override
        public List<GeneratedCard> generate(GenerationPrompt prompt) {
            this.seen = prompt;
            return answer;
        }
    }

    @BeforeEach
    void createTopic() {
        topic = topics.save(new Topic(USER, "DBMS", "dbms", clock.instant()));
    }

    private CardGenerator generatorReturning(List<GeneratedCard> answer) {
        return new CardGenerator(topics, cards, new RecordingClient(answer));
    }

    @Nested
    @DisplayName("counting")
    class Counting {

        @Test
        @DisplayName("clamps a count above the maximum instead of refusing it")
        void clampsAboveMaximum() {
            RecordingClient client = new RecordingClient(List.of(new GeneratedCard("Q", "A")));
            CardGenerator generator = new CardGenerator(topics, cards, client);

            generator.generate(USER, topic.getId(), null, 99);

            assertEquals(10, client.seen.count(), "asking for too many is not a mistake worth refusing");
        }

        @Test
        @DisplayName("refuses a count of zero or below")
        void refusesZero() {
            CardGenerator generator = generatorReturning(List.of(new GeneratedCard("Q", "A")));

            assertThrows(IllegalArgumentException.class,
                    () -> generator.generate(USER, topic.getId(), null, 0),
                    "there is no request that means give me no cards other than a bug");
        }

        @Test
        @DisplayName("defaults to eight when no count is given")
        void defaultsToEight() {
            RecordingClient client = new RecordingClient(List.of(new GeneratedCard("Q", "A")));
            CardGenerator generator = new CardGenerator(topics, cards, client);

            generator.generate(USER, topic.getId(), null, null);

            assertEquals(8, client.seen.count(), "eight is where review still means reading");
        }
    }

    @Nested
    @DisplayName("validating what came back")
    class Validating {

        @Test
        @DisplayName("drops an unusable candidate and keeps the rest")
        void dropsUnusableCandidates() {
            CardGenerator generator = generatorReturning(List.of(
                    new GeneratedCard("Good question", "Good answer"),
                    new GeneratedCard("  ", "No question"),
                    new GeneratedCard("No answer", null)));

            List<GeneratedCard> generated = generator.generate(USER, topic.getId(), null, 3);

            assertEquals(1, generated.size(), "nine good cards should not be lost to one bad one");
            assertEquals("Good question", generated.get(0).front(), "the usable card should survive");
        }

        @Test
        @DisplayName("refuses when every candidate was unusable")
        void refusesWhenAllDropped() {
            CardGenerator generator = generatorReturning(List.of(new GeneratedCard("", "")));

            assertThrows(GenerationRefusedException.class,
                    () -> generator.generate(USER, topic.getId(), null, 1),
                    "an empty batch is different from a batch that needs retrying");
        }
    }

    @Nested
    @DisplayName("the avoid-list")
    class AvoidList {

        @Test
        @DisplayName("passes the topic's existing fronts to the model")
        void passesExistingFronts() {
            cards.save(new dev.vsdeadshot.flashcards.domain.Card(
                    USER, topic, "What is 3NF?", "Third normal form.",
                    java.time.LocalDate.now(clock), clock.instant(), null));
            RecordingClient client = new RecordingClient(List.of(new GeneratedCard("Q", "A")));
            CardGenerator generator = new CardGenerator(topics, cards, client);

            generator.generate(USER, topic.getId(), "normalization", 3);

            assertTrue(client.seen.avoid().contains("What is 3NF?"),
                    "the model should be told what the deck already covers");
        }
    }
}
```

Note: the `Card` constructor arguments above must match the real one. Check
`backend/src/main/java/dev/vsdeadshot/flashcards/domain/Card.java` first and adjust the call — do
not guess.

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./gradlew test --tests '*CardGeneratorTest'`
Expected: FAIL — `CardGenerator` does not exist.

- [ ] **Step 3: Add the avoid-list query to `CardRepository`**

```java
    /**
     * Fronts only. Backs are the bulk of a card's content and never leave this machine; a front
     * is short and is what actually determines whether a new card is a duplicate.
     */
    @Query("""
            select c.front from Card c
            where c.userId = :userId and c.topic.id = :topicId and c.archived = false
            order by c.id desc
            """)
    List<String> findRecentFronts(
            @Param("userId") String userId, @Param("topicId") Long topicId, Limit limit);
```

- [ ] **Step 4: Write `CardGenerator`**

```java
package dev.vsdeadshot.flashcards.service;

import dev.vsdeadshot.flashcards.ai.GeneratedCard;
import dev.vsdeadshot.flashcards.ai.GeminiClient;
import dev.vsdeadshot.flashcards.ai.GenerationPrompt;
import dev.vsdeadshot.flashcards.ai.GenerationRefusedException;
import dev.vsdeadshot.flashcards.domain.Topic;
import dev.vsdeadshot.flashcards.repository.CardRepository;
import dev.vsdeadshot.flashcards.repository.TopicRepository;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles a prompt, calls the generator, and decides what came back is usable.
 *
 * <p>Lives here rather than in {@code ai/} because it needs repositories, and {@code ai/} is kept
 * free of persistence the same way {@code scheduler/} is.
 */
@Service
public class CardGenerator {

    static final int DEFAULT_COUNT = 8;
    static final int MAX_COUNT = 10;
    static final int MAX_FIELD_LENGTH = 10_000;
    private static final Limit AVOID_LIMIT = Limit.of(50);

    private final TopicRepository topics;
    private final CardRepository cards;
    private final GeminiClient gemini;

    public CardGenerator(TopicRepository topics, CardRepository cards, GeminiClient gemini) {
        this.topics = topics;
        this.cards = cards;
        this.gemini = gemini;
    }

    @Transactional(readOnly = true)
    public List<GeneratedCard> generate(String userId, long topicId, String focus, Integer count) {
        Topic topic = topics.findByIdAndUserId(topicId, userId)
                .orElseThrow(() -> new NotFoundException("No topic " + topicId));

        // Clamped above, refused at or below zero -- the same asymmetry the study queue's limit
        // uses, and for the same reason.
        int requested = count == null ? DEFAULT_COUNT : count;
        if (requested <= 0) {
            throw new IllegalArgumentException("count must be greater than zero, was " + requested);
        }
        int effective = Math.min(requested, MAX_COUNT);

        List<String> avoid = cards.findRecentFronts(userId, topicId, AVOID_LIMIT);
        List<GeneratedCard> generated =
                gemini.generate(new GenerationPrompt(topic.getName(), focus, avoid, effective));

        List<GeneratedCard> usable = generated.stream().filter(CardGenerator::usable).toList();
        if (usable.isEmpty()) {
            throw new GenerationRefusedException("The card generator returned nothing usable.");
        }
        return usable;
    }

    private static boolean usable(GeneratedCard card) {
        return present(card.front()) && present(card.back());
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank() && value.length() <= MAX_FIELD_LENGTH;
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd backend && ./gradlew test --tests '*CardGeneratorTest'`
Expected: PASS, 6 tests.

- [ ] **Step 6: Write the DTOs**

`GenerateRequest.java`:

```java
package dev.vsdeadshot.flashcards.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * {@code count} carries no {@code @Min}/{@code @Max}: CardGenerator already answers 400 naming
 * the offending value, and a Bean Validation annotation would intercept one layer earlier and
 * replace that with "Invalid request content." Same reasoning as ReviewRequest's confidence.
 *
 * <p>{@code focus} does carry {@code @Size}, because an over-long one would otherwise be sent
 * upstream and billed for before anything rejected it.
 */
public record GenerateRequest(
        @NotNull Long topicId,
        @Size(max = 200) String focus,
        Integer count) {
}
```

`CandidateResponse.java`:

```java
package dev.vsdeadshot.flashcards.web.dto;

import dev.vsdeadshot.flashcards.ai.GeneratedCard;

/**
 * Deliberately not CardResponse. That is documented as the only card shape because the client
 * caches by id; a candidate has no id, no schedule and no row, and giving it one with empty
 * fields would be the client's problem permanently.
 */
public record CandidateResponse(String front, String back) {

    public static CandidateResponse from(GeneratedCard card) {
        return new CandidateResponse(card.front(), card.back());
    }
}
```

`GenerateResponse.java`:

```java
package dev.vsdeadshot.flashcards.web.dto;

import java.util.List;

/**
 * A wrapper rather than a bare array, breaking from [Topic] and [Card]. Those are collections of
 * stored resources; this is a computed batch, and a wrapper leaves room for the one field likely
 * to be wanted later -- a note that output was truncated or filtered -- without a breaking change.
 */
public record GenerateResponse(List<CandidateResponse> candidates) {
}
```

- [ ] **Step 7: Add the endpoint to `CardController`**

Add the `CardGenerator` to the constructor and:

```java
    /**
     * Nothing is created server-side, so this is a 200 and not a 201, and the path is
     * action-shaped like {@code POST /study/{cardId}/review} rather than naming a resource that
     * does not exist.
     */
    @PostMapping("/generate")
    public GenerateResponse generate(
            @RequestAttribute(ApiKeyFilter.USER_ID_ATTRIBUTE) String userId,
            @Valid @RequestBody GenerateRequest request) {
        return new GenerateResponse(
                generator.generate(userId, request.topicId(), request.focus(), request.count())
                        .stream()
                        .map(CandidateResponse::from)
                        .toList());
    }
```

- [ ] **Step 8: Add the two error mappings to `ApiExceptionHandler`**

```java
    /**
     * A rate limit, an upstream outage, a timeout, or no key configured. All the same to a caller:
     * not your fault, try again shortly.
     */
    @ExceptionHandler(GenerationUnavailableException.class)
    public ProblemDetail handleGenerationUnavailable(GenerationUnavailableException e) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Generation unavailable", e.getMessage());
    }

    /**
     * Distinct from unavailable on purpose: an identical retry produces the same nothing, so
     * inviting one would be a lie.
     */
    @ExceptionHandler(GenerationRefusedException.class)
    public ProblemDetail handleGenerationRefused(GenerationRefusedException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Generation refused", e.getMessage());
    }
```

`GenerationMisconfiguredException` is deliberately **not** mapped. Nothing maps `Exception`, so it
becomes a `500` with no detail — which is the honest answer for our own credential being wrong,
and the one that leaks nothing about it. Log it where it is thrown, not here.

- [ ] **Step 9: Update `docs/api-contract.md`**

Add to the endpoint table, after the `POST /study/{cardId}/review` row:

```markdown
| `POST` | `/cards/generate` | `{topicId, focus?, count?}` | `200` + `{candidates: [{front, back}]}` |
```

Add to **Request limits**:

```markdown
`focus` is capped at 200 characters. `count` defaults to 8 and is treated the same way the
queue's `limit` is — **clamped** above its maximum of 10, and a **`400`** at zero or below.
Eight is chosen for review ergonomics rather than for the model: past ten, a person skims and
rubber-stamps, which defeats the only thing reviewing before saving is for.
```

Add to **Errors**, extending the status list:

```markdown
`422` the generator answered with nothing usable · `503` the generator is rate-limited, down, or
not configured. The two are deliberately different: `503` invites a retry and `422` does not.
```

- [ ] **Step 10: Run the whole suite**

Run: `cd backend && ./gradlew build`
Expected: PASS.

- [ ] **Step 11: Commit**

```bash
git add backend/src/main/java/dev/vsdeadshot/flashcards backend/src/test/java/dev/vsdeadshot/flashcards/service/CardGeneratorTest.java docs/api-contract.md
git commit -F - <<'EOF'
feat(backend): Add POST /cards/generate

The endpoint, the service that assembles the prompt, and the two error
mappings. Nothing is stored server-side, so this answers 200 rather
than 201, and the path is action-shaped like POST /study/{id}/review
rather than naming a resource that does not exist.

count reuses the study queue's asymmetric rule exactly: clamped above
the maximum, 400 at or below zero. Eight is the default for review
ergonomics, not for the model -- past ten a person skims, which defeats
reviewing before saving.

Validation drops rather than fails. One malformed candidate should not
cost the other nine. An entirely unusable batch is a 422, which is
deliberately not the 503 that a rate limit or an outage produces: a 503
invites a retry and a 422 would be lying if it did.

The avoid-list sends fronts only, capped at 50. Fronts are short and
are what determines duplication; backs are the bulk of the content and
never leave the machine.

Ownership follows the existing rule -- an unknown or another user's
topicId reads as 404, never 403.
EOF
```

---

### Task 4: The local candidate table

**Files:**
- Create: `android/app/src/main/java/dev/vsdeadshot/flashcards/data/local/CandidateEntity.java`
- Create: `android/app/src/main/java/dev/vsdeadshot/flashcards/data/local/CandidateDao.java`
- Create: `android/app/src/main/java/dev/vsdeadshot/flashcards/data/CandidateRepository.java`
- Modify: `android/app/src/main/java/dev/vsdeadshot/flashcards/data/local/FlashcardsDatabase.java`
- Test: `android/app/src/test/java/dev/vsdeadshot/flashcards/data/CandidateRepositoryTest.java`

**Interfaces:**
- Consumes: `CardRepository.create(long topicId, String front, String back)` returning `CardEntity` (existing).
- Produces:
  - `CandidateEntity` with fields `long id`, `long topicId`, `String front`, `String back`, `Instant generatedAt`
  - `CandidateDao` with `insertAll(List<CandidateEntity>)`, `List<CandidateEntity> all()`, `CandidateEntity find(long id)`, `void delete(long id)`, `void deleteAll()`, `int count()`
  - `CandidateRepository` with `void store(long topicId, List<CandidateEntity>)`, `List<CandidateEntity> all()`, `CardEntity accept(long candidateId)`, `void discard(long candidateId)`, `void discardAll()`

- [ ] **Step 1: Write the failing test**

Create `CandidateRepositoryTest.java`:

```java
package dev.vsdeadshot.flashcards.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import dev.vsdeadshot.flashcards.data.local.CandidateEntity;
import dev.vsdeadshot.flashcards.data.local.CardEntity;
import dev.vsdeadshot.flashcards.data.local.FlashcardsDatabase;
import dev.vsdeadshot.flashcards.data.local.TopicEntity;
import java.time.Instant;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class CandidateRepositoryTest {

    private FlashcardsDatabase db;
    private CandidateRepository candidates;
    private CardRepository cards;

    @Before
    public void openDatabase() {
        db = Room.inMemoryDatabaseBuilder(
                        ApplicationProvider.getApplicationContext(), FlashcardsDatabase.class)
                .allowMainThreadQueries()
                .build();
        candidates = new CandidateRepository(db);
        cards = new CardRepository(db);
        db.topics().upsertAll(List.of(new TopicEntity(1L, "DBMS", "dbms")));
    }

    @After
    public void closeDatabase() {
        db.close();
    }

    private static CandidateEntity candidate(String front, String back) {
        CandidateEntity entity = new CandidateEntity();
        entity.topicId = 1L;
        entity.front = front;
        entity.back = back;
        entity.generatedAt = Instant.parse("2026-08-18T10:00:00Z");
        return entity;
    }

    @Test
    public void storedCandidatesComeBackInInsertionOrder() {
        candidates.store(1L, List.of(candidate("Q1", "A1"), candidate("Q2", "A2")));

        List<CandidateEntity> stored = candidates.all();

        assertEquals("both candidates should be stored", 2, stored.size());
        assertEquals("the first candidate should come back first", "Q1", stored.get(0).front);
    }

    @Test
    public void acceptingACandidateCreatesACardAndRemovesTheCandidate() {
        candidates.store(1L, List.of(candidate("Q1", "A1")));
        long id = candidates.all().get(0).id;

        CardEntity created = candidates.accept(id);

        assertNotNull("accepting should produce a card", created);
        assertEquals("the card should carry the candidate's question", "Q1", created.front);
        assertNull("the candidate row should be gone once accepted",
                db.candidates().find(id));
    }

    @Test
    public void anAcceptedCandidateBecomesAnUnsyncedCardTheOutboxWillSend() {
        candidates.store(1L, List.of(candidate("Q1", "A1")));

        CardEntity created = candidates.accept(candidates.all().get(0).id);

        assertNull("a card written here has no server id until the create is accepted",
                created.serverId);
        assertTrue("the card should be offered to the sync as a pending create",
                db.cards().pendingCreates().stream().anyMatch(c -> c.id == created.id));
    }

    @Test
    public void discardingRemovesOnlyThatCandidate() {
        candidates.store(1L, List.of(candidate("Q1", "A1"), candidate("Q2", "A2")));
        long first = candidates.all().get(0).id;

        candidates.discard(first);

        assertEquals("the other candidate should be untouched", 1, candidates.all().size());
    }

    @Test
    public void discardingAllEmptiesTheBand() {
        candidates.store(1L, List.of(candidate("Q1", "A1"), candidate("Q2", "A2")));

        candidates.discardAll();

        assertTrue("discarding all should leave nothing", candidates.all().isEmpty());
    }

    @Test
    public void storingAFreshBatchReplacesTheOneBefore() {
        candidates.store(1L, List.of(candidate("Old", "A")));
        candidates.store(1L, List.of(candidate("New", "A")));

        List<CandidateEntity> stored = candidates.all();

        assertEquals("a new generation replaces the previous batch", 1, stored.size());
        assertEquals("only the newest batch should remain", "New", stored.get(0).front);
    }
}
```

Note: `TopicEntity` and `CardEntity` field/constructor shapes must match the real classes. Read
`data/local/TopicEntity.java` and `data/local/CardEntity.java` before writing, and adjust the
fixtures — do not guess.

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*CandidateRepositoryTest'`
Expected: FAIL — `CandidateEntity` does not exist.

- [ ] **Step 3: Write the entity**

```java
package dev.vsdeadshot.flashcards.data.local;

import androidx.room.Entity;
import androidx.room.PrimaryKey;
import java.time.Instant;

/**
 * A generated card the user has not accepted yet.
 *
 * <p>A separate table rather than a flag on {@code card}, because a candidate must never appear
 * in the study queue, in the outbox, or in a pull's delete-scope — and a flag would leave it one
 * forgotten {@code where} clause away from all three. Same reasoning that keeps a queued review
 * in {@code pending_review} instead of flagging its card.
 */
@Entity(tableName = "candidate")
public class CandidateEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    /** The topic the batch was generated for; already a local row when the batch was stored. */
    public long topicId;

    public String front;

    public String back;

    public Instant generatedAt;
}
```

- [ ] **Step 4: Write the DAO**

```java
package dev.vsdeadshot.flashcards.data.local;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import java.util.List;

@Dao
public interface CandidateDao {

    @Insert
    void insertAll(List<CandidateEntity> candidates);

    /** Insertion order, which is the order the model produced them in. */
    @Query("select * from candidate order by id")
    List<CandidateEntity> all();

    @Query("select * from candidate where id = :id")
    CandidateEntity find(long id);

    @Query("delete from candidate where id = :id")
    void delete(long id);

    @Query("delete from candidate")
    void deleteAll();

    @Query("select count(*) from candidate")
    int count();
}
```

- [ ] **Step 5: Register the entity and add the migration**

In `FlashcardsDatabase.java`, add `CandidateEntity.class` to `entities`, bump `version` to `6`,
add `public abstract CandidateDao candidates();`, add the migration to wherever `MIGRATION_4_5`
is registered, and:

```java
    /**
     * Candidates are droppable, like topics and unlike pending_review or an unsynced card: one
     * costs a single API call to recreate, and holding a migration hostage to one is the worse
     * trade. Created rather than altered, because version 5 had no such table.
     */
    static final Migration MIGRATION_5_6 = new Migration(5, 6) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("""
                    create table if not exists candidate (
                        id integer primary key autoincrement not null,
                        topicId integer not null,
                        front text,
                        back text,
                        generatedAt integer
                    )
                    """);
        }
    };
```

- [ ] **Step 6: Write the repository**

```java
package dev.vsdeadshot.flashcards.data;

import dev.vsdeadshot.flashcards.data.local.CandidateEntity;
import dev.vsdeadshot.flashcards.data.local.CardEntity;
import dev.vsdeadshot.flashcards.data.local.FlashcardsDatabase;
import java.util.List;

/** Blocking and composed, like every other repository here. A view model runs it on Graph.io(). */
public final class CandidateRepository {

    private final FlashcardsDatabase db;
    private final CardRepository cards;

    public CandidateRepository(FlashcardsDatabase db) {
        this.db = db;
        this.cards = new CardRepository(db);
    }

    /**
     * One batch at a time. A second generation replaces the first rather than accumulating,
     * because the band is a review queue and not a history — and an unbounded band would quietly
     * become a second, worse card list.
     */
    public void store(long topicId, List<CandidateEntity> candidates) {
        db.runInTransaction(() -> {
            db.candidates().deleteAll();
            db.candidates().insertAll(candidates);
        });
    }

    public List<CandidateEntity> all() {
        return db.candidates().all();
    }

    /**
     * Writes the card through the same path a hand-written one takes, so an accepted candidate
     * rides the outbox identically and works with the radio off. Both halves in one transaction:
     * a card created without its candidate removed would be offered for review a second time.
     */
    public CardEntity accept(long candidateId) {
        return db.runInTransaction(() -> {
            CandidateEntity candidate = db.candidates().find(candidateId);
            if (candidate == null) {
                return null;
            }
            CardEntity created = cards.create(candidate.topicId, candidate.front, candidate.back);
            db.candidates().delete(candidateId);
            return created;
        });
    }

    public void discard(long candidateId) {
        db.candidates().delete(candidateId);
    }

    public void discardAll() {
        db.candidates().deleteAll();
    }
}
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*CandidateRepositoryTest'`
Expected: PASS, 6 tests.

- [ ] **Step 8: Run the whole build**

Run: `cd android && ./gradlew build`
Expected: PASS including lint.

- [ ] **Step 9: Commit**

```bash
git add android/app/src/main/java/dev/vsdeadshot/flashcards/data android/app/src/test/java/dev/vsdeadshot/flashcards/data/CandidateRepositoryTest.java
git commit -F - <<'EOF'
feat(android): Add the local candidate table

A generated card the user has not accepted yet, in its own table
rather than as a flag on card. A candidate must never reach the study
queue, the outbox, or a pull's delete-scope, and a flag would leave it
one forgotten where clause away from all three -- the same reasoning
that keeps a queued review in pending_review instead of marking its
card.

Accepting writes the card through the existing offline-authoring path,
so an accepted candidate rides the outbox exactly like a hand-written
one and works with the radio off. Both halves run in one transaction,
because a card created without its candidate removed would be offered
for review a second time.

One batch at a time: a second generation replaces the first. The band
is a review queue, not a history, and an unbounded one would quietly
become a second and worse card list.

Candidates are droppable in a destructive migration, like topic and
unlike pending_review or an unsynced card. One costs a single API call
to recreate, and holding a migration hostage to that is the worse
trade.
EOF
```

---

### Task 5: The remote call

**Files:**
- Create: `android/app/src/main/java/dev/vsdeadshot/flashcards/data/remote/dto/GenerateRequestDto.java`
- Create: `android/app/src/main/java/dev/vsdeadshot/flashcards/data/remote/dto/GenerateResponseDto.java`
- Create: `android/app/src/main/java/dev/vsdeadshot/flashcards/data/remote/dto/CandidateDto.java`
- Create: `android/app/src/main/java/dev/vsdeadshot/flashcards/data/remote/TimeoutInterceptor.java`
- Modify: `android/app/src/main/java/dev/vsdeadshot/flashcards/data/remote/FlashcardsApi.java`
- Modify: `android/app/src/main/java/dev/vsdeadshot/flashcards/data/remote/ApiClient.java`
- Modify: `android/app/src/main/java/dev/vsdeadshot/flashcards/data/CandidateRepository.java`
- Test: `android/app/src/test/java/dev/vsdeadshot/flashcards/data/remote/GenerateApiTest.java`

**Interfaces:**
- Consumes: `CandidateRepository.store(long, List<CandidateEntity>)` (Task 4).
- Produces:
  - `class GenerateRequestDto { public long topicId; public String focus; public int count; }`
  - `class CandidateDto { public String front; public String back; }`
  - `class GenerateResponseDto { public List<CandidateDto> candidates; }`
  - `FlashcardsApi.generate(GenerateRequestDto body)` returning `Call<GenerateResponseDto>`
  - `TimeoutInterceptor` reading header `X-Read-Timeout-Seconds`
  - `CandidateRepository.generate(long topicId, String focus, int count)` returning `int` (how many were stored)

- [ ] **Step 1: Write the failing test**

Create `GenerateApiTest.java` following the existing `FlashcardsApiTest` structure (read it first
for the `respond` / `respondWithProblem` helpers and the `ApiClient.create(url, key)` call):

```java
package dev.vsdeadshot.flashcards.data.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import dev.vsdeadshot.flashcards.data.remote.dto.GenerateRequestDto;
import dev.vsdeadshot.flashcards.data.remote.dto.GenerateResponseDto;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class GenerateApiTest {

    private static final String KEY = "test-key";

    private MockWebServer server;
    private FlashcardsApi api;

    @Before
    public void startServer() throws Exception {
        server = new MockWebServer();
        server.start();
        api = ApiClient.create(server.url("/api/v1/").toString(), KEY);
    }

    @After
    public void stopServer() throws Exception {
        server.close();
    }

    private static GenerateRequestDto request() {
        GenerateRequestDto body = new GenerateRequestDto();
        body.topicId = 2L;
        body.focus = "normalization";
        body.count = 8;
        return body;
    }

    @Test
    public void aGeneratedBatchIsParsedIntoCandidates() throws Exception {
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "application/json")
                .body("{\"candidates\":[{\"front\":\"Q1\",\"back\":\"A1\"},"
                        + "{\"front\":\"Q2\",\"back\":\"A2\"}]}")
                .build());

        GenerateResponseDto response = api.generate(request()).execute().body();

        assertEquals("both candidates should parse", 2, response.candidates.size());
        assertEquals("the first question should survive", "Q1", response.candidates.get(0).front);
    }

    @Test
    public void theRequestCarriesTheTopicFocusAndCount() throws Exception {
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "application/json")
                .body("{\"candidates\":[]}")
                .build());

        api.generate(request()).execute();
        RecordedRequest sent = server.takeRequest();

        String body = sent.getBody().utf8();
        assertTrue("the topic must be sent", body.contains("\"topicId\":2"));
        assertTrue("the focus must be sent", body.contains("normalization"));
        assertTrue("the count must be sent", body.contains("\"count\":8"));
    }

    @Test
    public void aGeneratorOutageIsRetryable() {
        server.enqueue(new MockResponse.Builder()
                .code(503)
                .setHeader("Content-Type", "application/problem+json")
                .body("{\"status\":503,\"title\":\"Generation unavailable\","
                        + "\"detail\":\"The card generator did not answer.\"}")
                .build());

        try {
            api.generate(request()).execute();
            fail("a 503 should be turned into an ApiException before Retrofit sees it");
        } catch (Exception e) {
            ApiException api = (ApiException) unwrap(e);
            assertEquals("the status should survive", 503, api.status());
            assertEquals("an outage is worth retrying",
                    ApiException.Disposition.RETRY, api.disposition());
        }
    }

    @Test
    public void aRefusedGenerationIsNotRetryable() {
        server.enqueue(new MockResponse.Builder()
                .code(422)
                .setHeader("Content-Type", "application/problem+json")
                .body("{\"status\":422,\"title\":\"Generation refused\","
                        + "\"detail\":\"The card generator returned nothing usable.\"}")
                .build());

        try {
            api.generate(request()).execute();
            fail("a 422 should be turned into an ApiException before Retrofit sees it");
        } catch (Exception e) {
            ApiException api = (ApiException) unwrap(e);
            assertEquals("the status should survive", 422, api.status());
            assertEquals("retrying an identical refused request changes nothing",
                    ApiException.Disposition.DROP, api.disposition());
        }
    }

    private static Throwable unwrap(Throwable e) {
        Throwable current = e;
        while (current != null && !(current instanceof ApiException)) {
            current = current.getCause();
        }
        return current;
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*GenerateApiTest'`
Expected: FAIL — `GenerateRequestDto` does not exist.

- [ ] **Step 3: Write the DTOs**

```java
package dev.vsdeadshot.flashcards.data.remote.dto;

/**
 * Plain classes, not records: Moshi's record support needs java.lang.Record reflection that
 * Android's runtime does not provide, so a record DTO compiles and then fails at runtime.
 */
public class GenerateRequestDto {

    public long topicId;

    public String focus;

    /** Primitive so a JSON null is refused rather than read as zero. */
    public int count;
}
```

```java
package dev.vsdeadshot.flashcards.data.remote.dto;

public class CandidateDto {

    public String front;

    public String back;
}
```

```java
package dev.vsdeadshot.flashcards.data.remote.dto;

import java.util.List;

/** A wrapper, matching the contract: the batch is computed, not a collection of stored rows. */
public class GenerateResponseDto {

    public List<CandidateDto> candidates;
}
```

- [ ] **Step 4: Write the timeout interceptor**

```java
package dev.vsdeadshot.flashcards.data.remote;

import androidx.annotation.NonNull;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Lets one call ask for a longer read timeout than the client's default.
 *
 * <p>Generation blocks for as long as a model takes, which is well past the 20 seconds every
 * other call needs. Raising the client's default would relax it for calls that should stay
 * strict, and a second Retrofit instance would mean two clients to configure identically; a
 * header consumed here keeps one client and declares the exception on the method that needs it.
 */
public final class TimeoutInterceptor implements Interceptor {

    static final String HEADER = "X-Read-Timeout-Seconds";

    @NonNull
    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        Request request = chain.request();
        String requested = request.header(HEADER);
        if (requested == null) {
            return chain.proceed(request);
        }
        // The header is ours and never goes to the server.
        Request stripped = request.newBuilder().removeHeader(HEADER).build();
        return chain.withReadTimeout(Integer.parseInt(requested), TimeUnit.SECONDS)
                .proceed(stripped);
    }
}
```

- [ ] **Step 5: Add the API method and register the interceptor**

In `FlashcardsApi.java`:

```java
    /**
     * The one call that can take a minute. The header is consumed by TimeoutInterceptor and never
     * reaches the server; 60 seconds sits above the backend's own 45-second upstream timeout, so
     * the server gives up first and this client hears a 503 rather than a socket timeout.
     */
    @Headers("X-Read-Timeout-Seconds: 60")
    @POST("cards/generate")
    Call<GenerateResponseDto> generate(@Body GenerateRequestDto body);
```

In `ApiClient.java`, add `.addInterceptor(new TimeoutInterceptor())` to the `OkHttpClient.Builder`
chain, **before** `ProblemInterceptor` so a slow call is not cut short while being classified.

- [ ] **Step 6: Add the generate path to `CandidateRepository`**

```java
    /**
     * Foreground work, not outbox work: this runs because a person pressed a button and is
     * waiting, so a failure is theirs to see and act on rather than something to queue and retry
     * behind their back. Nothing here touches SyncEngine.
     */
    public int generate(long topicId, String focus, int count) throws IOException {
        GenerateRequestDto body = new GenerateRequestDto();
        body.topicId = topicId;
        body.focus = focus;
        body.count = count;

        GenerateResponseDto response = api.generate(body).execute().body();
        List<CandidateEntity> candidates = new ArrayList<>();
        for (CandidateDto dto : response.candidates) {
            CandidateEntity entity = new CandidateEntity();
            entity.topicId = topicId;
            entity.front = dto.front;
            entity.back = dto.back;
            entity.generatedAt = clock.instant();
            candidates.add(entity);
        }
        store(topicId, candidates);
        return candidates.size();
    }
```

Add a **second constructor** `CandidateRepository(FlashcardsDatabase db, FlashcardsApi api,
Clock clock)`, keeping the existing `CandidateRepository(FlashcardsDatabase db)` working — Task 4's
tests construct it that way and must keep passing. `CardRepository` already has exactly this pair
of constructors; follow it. The one-argument form leaves `api` null, which is correct: a caller
that only reads and accepts candidates never generates.

- [ ] **Step 7: Run the tests**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*GenerateApiTest'`
Expected: PASS, 4 tests.

Then: `cd android && ./gradlew build`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add android/app/src/main/java/dev/vsdeadshot/flashcards/data android/app/src/test/java/dev/vsdeadshot/flashcards/data/remote/GenerateApiTest.java
git commit -F - <<'EOF'
feat(android): Call the generate endpoint

The API method, the DTOs, and a per-call read timeout.

Generation blocks for as long as a model takes, well past the 20
seconds every other call needs. Raising the client's default would
relax it for calls that should stay strict, and a second Retrofit
instance would mean two clients to keep configured identically. A
header consumed by an interceptor keeps one client and declares the
exception on the one method that needs it. It never reaches the server.

Sixty seconds sits above the backend's own 45-second upstream timeout,
so the server gives up first and this client hears a 503 rather than a
socket timeout it would have to guess the meaning of.

This is foreground work and deliberately not outbox work: it runs
because somebody pressed a button and is waiting, so a failure is
theirs to see rather than something to queue and retry behind their
back. Nothing here touches SyncEngine.
EOF
```

---

### Task 6: The generate bottom sheet

**Files:**
- Create: `android/app/src/main/res/layout/sheet_generate.xml`
- Create: `android/app/src/main/java/dev/vsdeadshot/flashcards/ui/cards/GenerateSheet.java`
- Create: `android/app/src/main/java/dev/vsdeadshot/flashcards/ui/cards/GenerateViewModel.java`
- Create: `android/app/src/main/res/menu/cards_menu.xml`
- Modify: `android/app/src/main/java/dev/vsdeadshot/flashcards/ui/cards/CardListFragment.java`
- Modify: `android/app/src/main/res/values/strings.xml`
- Modify: `android/app/src/main/java/dev/vsdeadshot/flashcards/ui/Graph.java`
- Test: `android/app/src/test/java/dev/vsdeadshot/flashcards/ui/cards/GenerateSheetTest.java`

**Interfaces:**
- Consumes: `CandidateRepository.generate(long, String, int)` (Task 5).
- Produces:
  - `GenerateViewModel` exposing `LiveData<GenerateState> state()` and `void generate(long topicId, String focus, int count)`
  - `record GenerateState(boolean running, Integer generated, Integer error)` nested in `GenerateViewModel`; `error` is a string **resource id**, so the view model never builds user-facing copy
  - `GenerateSheet extends BottomSheetDialogFragment` with `static GenerateSheet newInstance()`

- [ ] **Step 1: Write the failing test**

```java
package dev.vsdeadshot.flashcards.ui.cards;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.view.View;
import android.widget.TextView;
import androidx.fragment.app.testing.FragmentScenario;
import dev.vsdeadshot.flashcards.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class GenerateSheetTest {

    @Test
    public void theSheetOffersATopicAFocusAndACount() {
        try (FragmentScenario<GenerateSheet> scenario =
                FragmentScenario.launch(GenerateSheet.class)) {
            scenario.onFragment(sheet -> {
                View view = sheet.requireView();
                assertNotNull("a topic picker is required", view.findViewById(R.id.generate_topic));
                assertNotNull("a focus field is required", view.findViewById(R.id.generate_focus));
                assertNotNull("a count field is required", view.findViewById(R.id.generate_count));
            });
        }
    }

    @Test
    public void anErrorIsShownInTheSheetSoTheInputsAreStillThereToRetryWith() {
        try (FragmentScenario<GenerateSheet> scenario =
                FragmentScenario.launch(GenerateSheet.class)) {
            scenario.onFragment(sheet -> {
                sheet.showError("The card generator is busy. Try again shortly.");

                TextView error = sheet.requireView().findViewById(R.id.generate_error);
                assertEquals("the message should be on screen", View.VISIBLE, error.getVisibility());
                assertTrue("the message should say what happened",
                        error.getText().toString().contains("busy"));
                assertEquals("the inputs must survive an error so a retry keeps them",
                        View.VISIBLE,
                        sheet.requireView().findViewById(R.id.generate_focus).getVisibility());
            });
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*GenerateSheetTest'`
Expected: FAIL — `GenerateSheet` does not exist.

- [ ] **Step 3: Write the layout**

`sheet_generate.xml` — a vertical `LinearLayout` containing:
- `TextInputLayout` + `MaterialAutoCompleteTextView` `@+id/generate_topic`, hint `@string/generate_topic`
- `TextInputLayout` + `TextInputEditText` `@+id/generate_focus`, hint `@string/generate_focus`
- `TextInputLayout` + `TextInputEditText` `@+id/generate_count`, `android:inputType="number"`
- `TextView` `@+id/generate_error`, `android:visibility="gone"`, `textColor="?attr/colorError"`
- `LinearProgressIndicator` `@+id/generate_progress`, `android:indeterminate="true"`, `visibility="gone"`
- `MaterialButton` `@+id/generate_go`, text `@string/generate_go`

- [ ] **Step 4: Add the strings**

```xml
    <string name="generate_title">Generate cards</string>
    <string name="generate_topic">Topic</string>
    <string name="generate_focus">Focus (optional)</string>
    <string name="generate_count">How many</string>
    <string name="generate_go">Generate</string>
    <!-- The three failures a person can tell apart and act on differently. -->
    <string name="generate_error_busy">The card generator is busy. Try again shortly.</string>
    <string name="generate_error_refused">The generator had nothing to add for that. Try a different focus.</string>
    <string name="generate_error_offline">Generating needs a connection.</string>
    <plurals name="generate_done">
        <item quantity="one">%d card ready to review</item>
        <item quantity="other">%d cards ready to review</item>
    </plurals>
```

- [ ] **Step 5: Write the view model**

```java
package dev.vsdeadshot.flashcards.ui.cards;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import dev.vsdeadshot.flashcards.data.CandidateRepository;
import dev.vsdeadshot.flashcards.data.remote.ApiException;
import dev.vsdeadshot.flashcards.ui.Graph;
import java.io.IOException;

/**
 * Deliberately does not subscribe to Room invalidation. The sheet shows the progress of one
 * request the user is waiting on; a background write has nothing to say about it.
 */
public class GenerateViewModel extends ViewModel {

    /** Exactly one of {@code generated} and {@code error} is set once {@code running} is false. */
    public record GenerateState(boolean running, Integer generated, Integer error) {
    }

    private final MutableLiveData<GenerateState> state = new MutableLiveData<>();
    private final CandidateRepository candidates = Graph.candidates();

    public LiveData<GenerateState> state() {
        return state;
    }

    public void generate(long topicId, String focus, int count) {
        state.setValue(new GenerateState(true, null, null));
        Graph.io().execute(() -> {
            try {
                int stored = candidates.generate(topicId, focus, count);
                state.postValue(new GenerateState(false, stored, null));
            } catch (ApiException e) {
                // 503 and 422 are separated on purpose: one invites a retry, the other does not.
                int message = e.status() == 422
                        ? dev.vsdeadshot.flashcards.R.string.generate_error_refused
                        : dev.vsdeadshot.flashcards.R.string.generate_error_busy;
                state.postValue(new GenerateState(false, null, message));
            } catch (IOException e) {
                state.postValue(new GenerateState(false, null,
                        dev.vsdeadshot.flashcards.R.string.generate_error_offline));
            }
        });
    }
}
```

- [ ] **Step 6: Write the sheet**

`GenerateSheet extends BottomSheetDialogFragment`: inflates `sheet_generate`, fills the topic
dropdown from `Graph.cards().topics()` on `Graph.io()`, defaults the count field to `8`, observes
`state()`, and:
- while `running`: progress visible, button disabled, error hidden
- on `generated`: `dismiss()`
- on `error`: progress hidden, button enabled, error visible

Expose `void showError(String message)` so the test can drive the error state without a network.

- [ ] **Step 7: Add the toolbar entry point**

`cards_menu.xml` with one item `@+id/action_generate`, `app:showAsAction="ifRoom"`, title
`@string/generate_title`. In `CardListFragment.onViewCreated`, add the menu provider and open the
sheet on that item.

The FAB is deliberately left alone: it means "new card", and it should keep meaning exactly one
thing. The toolbar already hosts the sync action, so a verb there has precedent.

- [ ] **Step 8: Add `Graph.candidates()`**

```java
    public static CandidateRepository candidates() {
        return new CandidateRepository(FlashcardsDatabase.get(context), api(), Clock.systemDefaultZone());
    }
```

The three-argument constructor, not the one Task 4's tests use — the sheet generates, so it needs
the API. Match the existing accessors' shape exactly, including how they reach the API instance —
read `Graph.java` first.

- [ ] **Step 9: Run the tests**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*GenerateSheetTest'`
Expected: PASS, 2 tests.

Then: `cd android && ./gradlew build`

- [ ] **Step 10: Commit**

```bash
git add android/app/src/main android/app/src/test/java/dev/vsdeadshot/flashcards/ui/cards/GenerateSheetTest.java
git commit -F - <<'EOF'
feat(android): Add the generate bottom sheet

Topic, optional focus and count, then progress in the same sheet.

The entry point is a toolbar action rather than the FAB. The FAB means
"new card" and should keep meaning exactly one thing; the toolbar
already hosts the sync action, so a verb up there has precedent.

Errors surface inside the sheet rather than as a snackbar, so the
inputs the user typed are still on screen to retry with. The three
cases read differently on purpose, because a person can act on the
difference: busy is worth retrying, refused means change the focus,
and offline means this is the one feature in the app that needs a
connection.

The view model does not subscribe to Room invalidation, unlike the
stats and card-list ones. It shows the progress of a single request
the user is waiting on, and a background write has nothing to say
about that.
EOF
```

---

### Task 7: The results band

**Files:**
- Create: `android/app/src/main/res/layout/item_candidate.xml`
- Create: `android/app/src/main/res/layout/item_candidate_header.xml`
- Modify: `android/app/src/main/java/dev/vsdeadshot/flashcards/ui/cards/CardListAdapter.java`
- Modify: `android/app/src/main/java/dev/vsdeadshot/flashcards/ui/cards/CardListViewModel.java`
- Modify: `android/app/src/main/java/dev/vsdeadshot/flashcards/ui/cards/CardListFragment.java`
- Modify: `android/app/src/main/res/values/strings.xml`
- Test: `android/app/src/test/java/dev/vsdeadshot/flashcards/ui/cards/CandidateBandTest.java`

**Interfaces:**
- Consumes: `CandidateRepository.all()`, `.accept(long)`, `.discard(long)`, `.discardAll()` (Task 4).
- Produces: `CardListViewModel.accept(long candidateId)`, `.discard(long candidateId)`, `.discardAll()`, and `LiveData<CardListView>` where `record CardListView(List<CandidateEntity> candidates, List<CardSummaryRow> cards)`.

- [ ] **Step 1: Write the failing test**

```java
package dev.vsdeadshot.flashcards.ui.cards;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import androidx.recyclerview.widget.RecyclerView;
import dev.vsdeadshot.flashcards.R;
import org.junit.Test;

/**
 * Reuses CardListFragmentTest's fixtures verbatim: openCardsTab(), settle(), row(list, position),
 * text(list, position, id), visibility(list, position, id), cachePulledCard(id, front) and
 * cacheTopic(). Copy them across rather than inventing new ones, so both files stay readable
 * against the same mental model.
 */
public class CandidateBandTest extends CardListTestSupport {

    @Test
    public void candidatesAppearAboveSavedCardsWithACountHeader() throws Exception {
        cacheTopic();
        cachePulledCard(1L, "What does ACID stand for?");
        cacheCandidates("Q1", "Q2");

        RecyclerView list = openCardsTab();

        assertEquals("the header should count the batch",
                "2 generated — review before saving",
                text(list, 0, R.id.candidate_header_text));
        assertEquals("the first candidate should sit directly under the header",
                "Q1", text(list, 1, R.id.candidate_front));
        assertEquals("saved cards come after the band",
                "What does ACID stand for?", text(list, 3, R.id.card_front));
    }

    @Test
    public void acceptingACandidateMovesItIntoTheDeck() throws Exception {
        cacheTopic();
        cacheCandidates("Q1", "Q2");
        RecyclerView list = openCardsTab();

        row(list, 1).findViewById(R.id.candidate_accept).performClick();
        settle();

        assertEquals("the band should be one shorter", 1, db.candidates().count());
        assertEquals("the accepted candidate should now be a card",
                1, db.cards().pendingCreates().size());
        assertEquals("the card should carry the candidate's question",
                "Q1", db.cards().pendingCreates().get(0).front);
    }

    @Test
    public void discardingACandidateRemovesItWithoutCreatingACard() throws Exception {
        cacheTopic();
        cacheCandidates("Q1", "Q2");
        RecyclerView list = openCardsTab();

        row(list, 1).findViewById(R.id.candidate_discard).performClick();
        settle();

        assertEquals("the band should be one shorter", 1, db.candidates().count());
        assertEquals("discarding must not write a card", 0, db.cards().pendingCreates().size());
    }

    @Test
    public void anEmptyBandDoesNotShowTheHeaderAtAll() throws Exception {
        cacheTopic();
        cachePulledCard(1L, "What does ACID stand for?");

        RecyclerView list = openCardsTab();

        assertNotEquals("with no candidates the screen looks exactly as it did before",
                "2 generated — review before saving",
                text(list, 0, R.id.card_front));
        assertEquals("only the saved card should be listed", 1, list.getAdapter().getItemCount());
    }
}
```

Add one fixture alongside the copied ones:

```java
    private void cacheCandidates(String... fronts) {
        List<CandidateEntity> batch = new ArrayList<>();
        for (String front : fronts) {
            CandidateEntity candidate = new CandidateEntity();
            candidate.topicId = 1L;
            candidate.front = front;
            candidate.back = "An answer";
            candidate.generatedAt = Instant.parse("2026-08-18T10:00:00Z");
            batch.add(candidate);
        }
        db.candidates().insertAll(batch);
    }
```

`CardListTestSupport` does not exist yet. Either extract the shared fixtures from
`CardListFragmentTest` into it as part of this task, or copy them into `CandidateBandTest` and
drop the `extends`. Extracting is preferable — two files needing the same six helpers is the
signal for it — but copying is acceptable if extraction turns out to disturb the existing test.

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*CandidateBandTest'`
Expected: FAIL.

- [ ] **Step 3: Write the two layouts**

`item_candidate_header.xml`: a `TextView` `@+id/candidate_header_text` and a `MaterialButton`
`@+id/candidate_discard_all` with text `@string/candidate_discard_all`.

`item_candidate.xml`: `TextView` `@+id/candidate_front`, `TextView` `@+id/candidate_back`
(`maxLines="2"`, `ellipsize="end"`), and two `MaterialButton`s `@+id/candidate_accept` and
`@+id/candidate_discard`.

- [ ] **Step 4: Add the strings**

```xml
    <plurals name="candidate_header">
        <item quantity="one">%d generated — review before saving</item>
        <item quantity="other">%d generated — review before saving</item>
    </plurals>
    <string name="candidate_accept">Add</string>
    <string name="candidate_discard">Discard</string>
    <string name="candidate_discard_all">Discard all</string>
```

- [ ] **Step 5: Give the adapter three view types**

Add `VIEW_TYPE_CANDIDATE_HEADER = 0`, `VIEW_TYPE_CANDIDATE = 1`, `VIEW_TYPE_CARD = 2`.
`getItemViewType(position)` returns the header for position 0 when candidates exist, a candidate
while `position <= candidates.size()`, and a card otherwise. `getItemCount()` is
`candidates.isEmpty() ? cards.size() : candidates.size() + 1 + cards.size()`.

A second view type rather than a `ConcatAdapter`: the band and the list scroll as one thing and
share a `RecyclerView`, and a concat would make the header's count depend on another adapter's
state.

- [ ] **Step 6: Extend the view model**

`CardListViewModel` already subscribes to Room invalidation. Add `candidate` to the observed
tables so accepting or discarding refreshes the list, and add the three methods, each running on
`Graph.io()`.

- [ ] **Step 7: Run the tests**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*CandidateBandTest'`
Expected: PASS, 4 tests. Then `./gradlew build`.

- [ ] **Step 8: Commit**

```bash
git add android/app/src/main android/app/src/test/java/dev/vsdeadshot/flashcards/ui/cards/CandidateBandTest.java
git commit -F - <<'EOF'
feat(android): Show generated candidates in the card list

A band above the saved cards, with a count header, per-row add and
discard, and discard-all.

A second view type in the existing adapter rather than a ConcatAdapter.
The band and the list scroll as one thing and share a RecyclerView, and
a concat would make the header's count depend on another adapter's
state -- two objects that would have to agree, which is the shape that
eventually disagrees.

The header is absent rather than empty when there are no candidates, so
the screen looks exactly as it did before this feature until the moment
a batch exists.

CardListViewModel now observes the candidate table too, so accepting or
discarding refreshes the list through the invalidation path it already
uses rather than a second mechanism.
EOF
```

---

### Task 8: Editing a candidate before accepting

**Files:**
- Modify: `android/app/src/main/res/navigation/nav_graph.xml`
- Modify: `android/app/src/main/java/dev/vsdeadshot/flashcards/ui/cards/CardEditorViewModel.java`
- Modify: `android/app/src/main/java/dev/vsdeadshot/flashcards/ui/cards/CardEditorFragment.java`
- Modify: `android/app/src/main/java/dev/vsdeadshot/flashcards/ui/cards/CardListFragment.java`
- Modify: `android/app/src/main/java/dev/vsdeadshot/flashcards/data/CandidateRepository.java`
- Modify: `android/app/src/main/res/values/strings.xml`
- Test: `android/app/src/test/java/dev/vsdeadshot/flashcards/ui/cards/CardEditorCandidateTest.java`

**Interfaces:**
- Consumes: everything above.
- Produces: `CandidateRepository.acceptEdited(long candidateId, long topicId, String front, String back)` returning `CardEntity`; nav argument `candidateId` with `defaultValue="0L"`.

- [ ] **Step 1: Write the failing test**

```java
package dev.vsdeadshot.flashcards.ui.cards;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import dev.vsdeadshot.flashcards.R;
import org.junit.Test;

/**
 * Reuses CardEditorFragmentTest's fixtures verbatim: openList(), settle(), text(id),
 * setText(id, value), currentDestination() and cacheTopic().
 */
public class CardEditorCandidateTest extends CardEditorTestSupport {

    @Test
    public void openingACandidateFillsTheEditorWithItsQuestionAndAnswer() throws Exception {
        cacheTopic();
        long id = cacheCandidate("What is 3NF?", "Third normal form.");
        openEditorForCandidate(id);

        assertEquals("the question should be prefilled", "What is 3NF?", text(R.id.editor_front));
        assertEquals("the answer should be prefilled", "Third normal form.", text(R.id.editor_back));
    }

    @Test
    public void savingAnEditedCandidateCreatesTheCardWithTheEditsNotTheOriginal() throws Exception {
        cacheTopic();
        long id = cacheCandidate("What is 3NF?", "Third normal form.");
        openEditorForCandidate(id);

        setText(R.id.editor_front, "What problem does 3NF solve?");
        activity.findViewById(R.id.editor_save).performClick();
        settle();

        assertEquals("the card should hold what the user typed, not what the model wrote",
                "What problem does 3NF solve?", db.cards().pendingCreates().get(0).front);
        assertNull("the candidate must not survive being accepted", db.candidates().find(id));
    }

    @Test
    public void theEditorIsTitledForAGeneratedCardRatherThanAnEdit() throws Exception {
        cacheTopic();
        openEditorForCandidate(cacheCandidate("What is 3NF?", "Third normal form."));

        assertEquals("a generated card is neither new nor an edit of a saved card",
                "Add generated card", activity.getSupportActionBar().getTitle().toString());
    }
}
```

Add two fixtures alongside the copied ones:

```java
    private long cacheCandidate(String front, String back) {
        CandidateEntity candidate = new CandidateEntity();
        candidate.topicId = 1L;
        candidate.front = front;
        candidate.back = back;
        candidate.generatedAt = Instant.parse("2026-08-18T10:00:00Z");
        db.candidates().insertAll(List.of(candidate));
        return db.candidates().all().get(0).id;
    }

    private void openEditorForCandidate(long candidateId) throws InterruptedException {
        Bundle args = new Bundle();
        args.putLong("candidateId", candidateId);
        args.putString("title", activity.getString(R.string.editor_title_generated));
        Navigation.findNavController(activity, R.id.nav_host)
                .navigate(R.id.action_cardList_to_cardEditor, args);
        settle();
    }
```

`CardEditorTestSupport` does not exist yet — same choice as Task 7: extract the shared fixtures
from `CardEditorFragmentTest`, or copy them in and drop the `extends`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*CardEditorCandidateTest'`
Expected: FAIL.

- [ ] **Step 3: Add the nav argument**

```xml
        <!-- Zero means "not a candidate", the same convention cardId already uses: local ids run
             downwards from zero and server ids start at one, so zero is never a real id. -->
        <argument
            android:name="candidateId"
            android:defaultValue="0L"
            app:argType="long" />
```

- [ ] **Step 4: Add `acceptEdited` to `CandidateRepository`**

```java
    /**
     * Accepting with the user's edits rather than the model's text. One transaction, for the same
     * reason accept() is: a card created without its candidate removed would come back for review.
     */
    public CardEntity acceptEdited(long candidateId, long topicId, String front, String back) {
        return db.runInTransaction(() -> {
            CardEntity created = cards.create(topicId, front, back);
            db.candidates().delete(candidateId);
            return created;
        });
    }
```

- [ ] **Step 5: Give the editor its second source**

`CardEditorViewModel` currently loads a card by id. Add: when `candidateId != 0`, load from
`CandidateRepository` instead and route save to `acceptEdited`. Keep the two sources behind one
`LiveData` so the fragment does not learn which it is looking at.

Add a comment recording that this is the cost the design accepted: one destination and one view
model serving two sources, chosen over a fourth destination.

- [ ] **Step 6: Open the editor from a candidate row**

In `CardListFragment`, tapping a candidate navigates to the editor with `candidateId` set and
`title` set to `@string/editor_title_generated` ("Add generated card"). Add that string.

- [ ] **Step 7: Run everything**

Run: `cd android && ./gradlew build`
Expected: PASS, all tests and lint.

- [ ] **Step 8: Update `CLAUDE.md`**

Add to the Android UI section:

```markdown
**Generation is the one thing that is not offline-first, and not outbox work.** `POST
/cards/generate` runs because a person pressed a button and is waiting, so its failure is theirs
to see rather than something queued and retried behind them — nothing about it touches
`SyncEngine` or `ApiException.disposition()`. Everything else that talks to the server does go
through that path, so the exception needs stating. Candidates live in their own table rather than
as a flag on `card`, because a candidate must never reach the study queue, the outbox or a pull's
delete-scope. Accepting one writes through the ordinary authoring path, so from that moment it is
an ordinary unsynced card.
```

- [ ] **Step 9: Commit**

```bash
git add android/app/src/main android/app/src/test docs CLAUDE.md
git commit -F - <<'EOF'
feat(android): Allow editing a candidate before accepting it

The editor destination gains a candidateId argument beside cardId, so
a generated card can be corrected before it enters the deck rather
than after.

No new destination: the editor is already titled from its arguments,
which is how one screen is "New card" or "Edit card", and this makes it
three. The real cost is that CardEditorViewModel now serves two
sources, and that was named in the design rather than discovered here.

Zero means "not a candidate", the same convention cardId already uses:
local ids run downwards from zero and server ids start at one, so zero
is the one value that is never a real id.

Saving routes to acceptEdited, which creates the card from what the
user typed rather than what the model wrote, and deletes the candidate
in the same transaction -- otherwise it would come back for review.
EOF
```

---

## After the plan

Run the app on the emulator (`emulator -avd flashcards_api36`) with the backend up and
`GEMINI_API_KEY` set, and generate a real batch. The test suite cannot see an inset, a truncated
string, or a sheet that dismisses too early — the toolbar bug found on 2026-08-18 is the standing
argument for this step.

Then confirm the one open question from the spec: whether `error.code` really is a string on the
wire, and fix `GeminiRestClient`'s failure mapping if not.
