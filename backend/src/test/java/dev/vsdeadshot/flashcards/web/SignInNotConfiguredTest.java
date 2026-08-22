package dev.vsdeadshot.flashcards.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * The suite configures no passphrase, which is what this class is here to exploit: signing in
 * has to stay optional while the API key is still a credential, or landing this change refuses
 * to start every deployment that has not set one yet.
 *
 * <p>Same posture {@code GeminiConfigurationTest} pins for generation — a capability that is
 * absent rather than a precondition that is missing. It stops being optional in the change that
 * removes the key, when it is the only way in.
 */
@AutoConfigureMockMvc
@DisplayName("Signing in with no passphrase configured")
class SignInNotConfiguredTest extends EmbeddedPostgresTest {

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("answers 503 rather than rejecting the caller's passphrase")
    void answersUnavailable() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"passphrase\":\"anything at all\"}"))
                // 401 would send somebody looking for a fault in what they typed, when the
                // server is the half that is not set up.
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("Sign-in unavailable"));
    }

    @Test
    @DisplayName("leaves every other endpoint working on the key")
    void everythingElseStillWorks() throws Exception {
        mvc.perform(get("/api/v1/topics").header(ApiKeyFilter.HEADER, TEST_API_KEY))
                .andExpect(status().isOk());
    }
}
