package dev.vsdeadshot.flashcards.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.vsdeadshot.flashcards.support.EmbeddedPostgresTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The one route in this application that answers without a credential.
 *
 * <p>Every assertion here is about that exemption being exactly as wide as intended: reachable
 * with no key, and disclosing nothing that would be worth reaching it for. Not
 * {@code @Transactional}, like the other controller tests, though nothing here writes.
 */
@AutoConfigureMockMvc
@DisplayName("The health endpoint")
class HealthControllerTest extends EmbeddedPostgresTest {

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("answers a probe that presents no key")
    void answersWithoutAKey() throws Exception {
        // The whole point. A probe cannot carry a credential, and a 401 here reads to a
        // platform as an instance that never became healthy.
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("answers the same when a key is presented, rather than treating one specially")
    void aKeyChangesNothing() throws Exception {
        mvc.perform(get("/health").header(ApiKeyFilter.HEADER, TEST_API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    /**
     * The exemption is a property of the path, so this is what pins how far it reaches. A
     * prefix match written one character shorter would open every route to anyone.
     */
    @Test
    @DisplayName("does not exempt anything under /api from the key")
    void theExemptionDoesNotReachTheApi() throws Exception {
        mvc.perform(get("/api/v1/topics"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/stats"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Read back as a whole rather than field by field. Asserting only that {@code status} is
     * "UP" would pass just as happily beside a version string or a database verdict, and this
     * body goes to anyone who asks for it.
     */
    @Test
    @DisplayName("discloses nothing beyond being up")
    void disclosesNothingElse() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"status\":\"UP\"}", true));
    }

    @Test
    @DisplayName("takes no body, so an unauthenticated caller cannot post one to it")
    void refusesAPost() throws Exception {
        // It sits outside the prefix RequestSizeLimitFilter guards, so the cap that stops an
        // oversized body does not apply here. Mapping GET alone is what makes that moot:
        // there is no handler to read a body into.
        mvc.perform(post("/health"))
                .andExpect(status().isMethodNotAllowed());
    }
}
