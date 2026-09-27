package dev.vsdeadshot.flashcards.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.vsdeadshot.flashcards.repository.LoginAttemptRepository;
import dev.vsdeadshot.flashcards.support.EmbeddedPostgresTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
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
     * The Android client reads a {@code 503} <em>without</em> a problem body as Render's router
     * answering while the instance wakes, and one <em>with</em> a body as this application
     * speaking. That rule holds only while every {@code 503} this application sends carries one,
     * so the body is pinned here rather than assumed from the handler returning a
     * {@code ProblemDetail}.
     */
    @Test
    @DisplayName("answers its 503 with a problem body, so it cannot pass for a gateway error")
    void unavailableCarriesAProblemBody() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"passphrase\":\"anything at all\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(503));
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

    /**
     * A hash nobody can sign in with does not invalidate the tokens already issued. The two are
     * separate: the hash is consulted only at {@code /auth/login}, and a token is checked
     * against its own row. So a deployment whose hash was mangled keeps working for anybody
     * already holding a token, and stops being able to admit anybody new — which is worth
     * pinning, because it is the shape of the outage and it is not the obvious one.
     */
    @Test
    @DisplayName("leaves a token issued earlier still working")
    void alreadyIssuedTokensStillWork() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/topics").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk());
    }
}
