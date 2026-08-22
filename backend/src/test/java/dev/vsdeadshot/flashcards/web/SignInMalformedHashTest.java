package dev.vsdeadshot.flashcards.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.vsdeadshot.flashcards.repository.LoginAttemptRepository;
import dev.vsdeadshot.flashcards.support.EmbeddedPostgresTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * What a caller is told when the server's hash is present but unusable.
 *
 * <p>This is the behaviour that cost two rounds of debugging against a live deployment. The
 * value below is a real bcrypt hash with its {@code $} sequences eaten, which is what a shell or
 * a dotenv parser does to one on its way into an environment variable. It is non-blank, so the
 * application used to call sign-in configured, run bcrypt against a string bcrypt cannot read,
 * and answer {@code 401} — telling whoever typed the correct passphrase that they had got it
 * wrong, while the actual fault was on the server.
 */
@AutoConfigureMockMvc
@DisplayName("Signing in with a malformed passphrase hash")
class SignInMalformedHashTest extends EmbeddedPostgresTest {

    private static final String MANGLED =
            "2a12IZSEMboJ/pxoWyeqzxjekOoV9Zi5uE89GCxf/hNSvnqu9UJzAuwbG";

    @DynamicPropertySource
    static void malformedHash(DynamicPropertyRegistry registry) {
        registry.add("flashcards.passphrase-hash", () -> MANGLED);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private LoginAttemptRepository attempts;

    @AfterEach
    void clearAttempts() {
        attempts.deleteAll();
    }

    @Test
    @DisplayName("answers 503 rather than blaming the caller with a 401")
    void answersUnavailable() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"passphrase\":\"anything at all\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("Sign-in unavailable"));
    }

    /**
     * A misconfigured server must not consume the caller's allowance. Otherwise an operator
     * fixing the hash would find sign-in still refused — now for a different reason — and the
     * two failures would be very hard to tell apart from the outside.
     */
    @Test
    @DisplayName("records no failed attempt, since no passphrase was ever checked")
    void spendsNoAllowance() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"passphrase\":\"anything at all\"}"))
                    .andExpect(status().isServiceUnavailable());
        }

        assertEquals(0, attempts.count(),
                "the configured check runs before the rate limit, so a server that cannot "
                        + "check a passphrase must not charge anybody for trying");
    }

    @Test
    @DisplayName("leaves every other endpoint working on the key")
    void everythingElseStillWorks() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/topics").header(ApiKeyFilter.HEADER, TEST_API_KEY))
                .andExpect(status().isOk());
    }
}
