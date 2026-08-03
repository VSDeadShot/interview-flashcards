package dev.vsdeadshot.flashcards.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.vsdeadshot.flashcards.support.EmbeddedPostgresTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Aimed at a route that deliberately does not exist, so a request the filter lets through
 * lands on nothing and returns {@code 404}. That keeps every assertion here about the filter
 * alone: {@code 401} means it rejected the request, {@code 404} means it did not, and no
 * controller's behaviour can change either answer.
 */
@AutoConfigureMockMvc
class ApiKeyFilterTest extends EmbeddedPostgresTest {

    private static final String PROTECTED_PATH = "/api/v1/no-such-route";

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("rejects a request with no key at all")
    void rejectsAMissingKey() throws Exception {
        mvc.perform(get(PROTECTED_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string("")); // the contract promises no body
    }

    @Test
    @DisplayName("rejects a wrong key")
    void rejectsAWrongKey() throws Exception {
        mvc.perform(get(PROTECTED_PATH).header(ApiKeyFilter.HEADER, "not-the-key"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("rejects a key that is merely a prefix of the real one")
    void rejectsAPrefixOfTheKey() throws Exception {
        String prefix = TEST_API_KEY.substring(0, TEST_API_KEY.length() - 1);

        mvc.perform(get(PROTECTED_PATH).header(ApiKeyFilter.HEADER, prefix))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("rejects an empty key rather than treating it as absent")
    void rejectsAnEmptyKey() throws Exception {
        mvc.perform(get(PROTECTED_PATH).header(ApiKeyFilter.HEADER, ""))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("lets the correct key through to the dispatcher")
    void acceptsTheCorrectKey() throws Exception {
        mvc.perform(get(PROTECTED_PATH).header(ApiKeyFilter.HEADER, TEST_API_KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("matches the header name case-insensitively, as HTTP requires")
    void headerNameIsCaseInsensitive() throws Exception {
        mvc.perform(get(PROTECTED_PATH).header("x-api-key", TEST_API_KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("leaves paths outside the API alone")
    void doesNotGuardNonApiPaths() throws Exception {
        mvc.perform(get("/something-else"))
                .andExpect(status().isNotFound());
    }
}
