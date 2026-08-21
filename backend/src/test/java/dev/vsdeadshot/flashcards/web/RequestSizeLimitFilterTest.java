package dev.vsdeadshot.flashcards.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.vsdeadshot.flashcards.domain.Topic;
import dev.vsdeadshot.flashcards.repository.CardRepository;
import dev.vsdeadshot.flashcards.repository.TopicRepository;
import dev.vsdeadshot.flashcards.support.EmbeddedPostgresTest;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Not {@code @Transactional}, for the reason every controller test here is not: a test
 * transaction would hold one Hibernate session open across the response, which production never
 * does. Rows are cleaned up by hand instead.
 */
@DisplayName("Request size limit")
@AutoConfigureMockMvc
class RequestSizeLimitFilterTest extends EmbeddedPostgresTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TopicRepository topics;

    @Autowired
    private CardRepository cards;

    private Topic topic;

    @BeforeEach
    void createTopic() {
        topic = topics.save(new Topic(TEST_USER_ID, "Sizes", "sizes", Instant.now()));
    }

    @AfterEach
    void cleanUp() {
        cards.deleteAll();
        topics.deleteAll();
    }

    /** A card body whose {@code front} is {@code frontChars} long. */
    private String cardJson(int frontChars) {
        return "{\"topicId\":" + topic.getId()
                + ",\"front\":\"" + "a".repeat(frontChars)
                + "\",\"back\":\"b\"}";
    }

    @Nested
    @DisplayName("with a declared length")
    class Declared {

        @Test
        @DisplayName("refuses a body over the cap before anything parses it")
        void refusesAnOversizedBody() throws Exception {
            // Deliberately well past the cap but nowhere near the 30MB that provoked this: the
            // point is the threshold, and a test should not allocate what it is guarding against.
            mvc.perform(post("/api/v1/cards")
                            .header(ApiKeyFilter.HEADER, TEST_API_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("x".repeat(RequestSizeLimitFilter.MAX_BYTES + 1)))
                    .andExpect(status().isContentTooLarge())
                    .andExpect(jsonPath("$.status").value(413))
                    .andExpect(jsonPath("$.title").value("Content too large"));
        }

        @Test
        @DisplayName("answers in problem+json like every other failure")
        void refusalIsProblemJson() throws Exception {
            String contentType = mvc.perform(post("/api/v1/cards")
                            .header(ApiKeyFilter.HEADER, TEST_API_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("x".repeat(RequestSizeLimitFilter.MAX_BYTES + 1)))
                    .andReturn()
                    .getResponse()
                    .getContentType();

            // Compared as a media type rather than as a string: the response names its charset
            // explicitly, which a string comparison against the bare type would call a mismatch.
            assertTrue(MediaType.parseMediaType(contentType)
                            .isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
                    "a client should not need a second parser for this one error, but got "
                            + contentType);
        }

        /**
         * The cap has to clear the largest card the contract allows, or it is not a guard against
         * abuse but a lower limit on legitimate use that nothing else documents.
         */
        @Test
        @DisplayName("still accepts a card at the contract's own size limit")
        void acceptsTheLargestLegalCard() throws Exception {
            mvc.perform(post("/api/v1/cards")
                            .header(ApiKeyFilter.HEADER, TEST_API_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(cardJson(10_000)))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("leaves an ordinary card alone")
        void acceptsAnOrdinaryCard() throws Exception {
            mvc.perform(post("/api/v1/cards")
                            .header(ApiKeyFilter.HEADER, TEST_API_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(cardJson(20)))
                    .andExpect(status().isCreated());
        }

        /**
         * The ordering claim in {@link RequestSizeLimitFilter}'s javadoc, asserted rather than
         * described. Nothing about a wrong order fails to compile, and the contract's promise is
         * that an unauthenticated request is answered {@code 401} and nothing else.
         */
        @Test
        @DisplayName("still answers 401, not 413, when the key is missing")
        void authenticationIsCheckedFirst() throws Exception {
            mvc.perform(post("/api/v1/cards")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("x".repeat(RequestSizeLimitFilter.MAX_BYTES + 1)))
                    .andExpect(status().isUnauthorized());
        }
    }

    /**
     * A chunked request declares no length and reports -1, which is how a check on
     * Content-Length alone is walked past. MockMvc always knows the length of the content it is
     * given, so the container's answer is stood in for directly rather than simulated through
     * the servlet stack.
     */
    @Nested
    @DisplayName("with no declared length")
    class Undeclared {

        private final RequestSizeLimitFilter filter = new RequestSizeLimitFilter();

        /** A request that reports no content length, whatever body it is actually holding. */
        private MockHttpServletRequest chunked(int bodyBytes) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/cards") {
                @Override
                public long getContentLengthLong() {
                    return -1;
                }
            };
            request.setContent("x".repeat(bodyBytes).getBytes(StandardCharsets.UTF_8));
            return request;
        }

        /** Drains the body the way a message converter would. */
        private FilterChain drain() {
            return (request, response) ->
                    ((HttpServletRequest) request).getInputStream().readAllBytes();
        }

        @Test
        @DisplayName("stops a body that runs past the cap while being read")
        void stopsAnOversizedChunkedBody() {
            MockHttpServletRequest request = chunked(RequestSizeLimitFilter.MAX_BYTES + 1);

            assertThrows(IOException.class,
                    () -> filter.doFilter(request, new MockHttpServletResponse(), drain()),
                    "a body with no declared length must still be bounded as it is read");
        }

        @Test
        @DisplayName("lets a body under the cap through untouched")
        void allowsAnAcceptableChunkedBody() throws Exception {
            MockHttpServletRequest request = chunked(1_024);
            MockHttpServletResponse response = new MockHttpServletResponse();
            boolean[] reached = {false};

            filter.doFilter(request, response, (req, res) -> {
                ((HttpServletRequest) req).getInputStream().readAllBytes();
                reached[0] = true;
            });

            assertEquals(200, response.getStatus(), "an acceptable body must not be refused");
            assertEquals(true, reached[0], "the request should have reached the chain");
        }

        @Test
        @DisplayName("does not guard paths outside the API")
        void ignoresNonApiPaths() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/something-else") {
                @Override
                public long getContentLengthLong() {
                    return -1;
                }
            };
            request.setContent("x".repeat(RequestSizeLimitFilter.MAX_BYTES + 1)
                    .getBytes(StandardCharsets.UTF_8));
            boolean[] reached = {false};

            filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                ((HttpServletRequest) req).getInputStream().readAllBytes();
                reached[0] = true;
            });

            assertEquals(true, reached[0], "only /api/ is guarded, matching the key filter");
        }
    }
}
