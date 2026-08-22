package dev.vsdeadshot.flashcards.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.vsdeadshot.flashcards.repository.AuthTokenRepository;
import dev.vsdeadshot.flashcards.repository.LoginAttemptRepository;
import dev.vsdeadshot.flashcards.service.LoginRateLimit;
import dev.vsdeadshot.flashcards.support.EmbeddedPostgresTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The limit as a caller experiences it.
 *
 * <p>Driven through HTTP because the ordering this pins is only observable there: the limit has
 * to be consulted before the passphrase is, or the bcrypt cost it exists to bound has already
 * been spent by the time it refuses anything.
 */
@AutoConfigureMockMvc
@DisplayName("Sign-in when the limit is spent")
class LoginRateLimitEndpointTest extends EmbeddedPostgresTest {

    private static final String PASSPHRASE = "a passphrase only this test knows";
    private static final String HASH = new BCryptPasswordEncoder(4).encode(PASSPHRASE);

    @DynamicPropertySource
    static void passphrase(DynamicPropertyRegistry registry) {
        registry.add("flashcards.passphrase-hash", () -> HASH);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private LoginAttemptRepository attempts;

    @Autowired
    private AuthTokenRepository tokens;

    @AfterEach
    void clear() {
        attempts.deleteAll();
        tokens.deleteAll();
    }

    private ResultActions attempt(String passphrase) throws Exception {
        return mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"passphrase\":\"" + passphrase + "\"}"));
    }

    private void exhaust() throws Exception {
        for (int i = 0; i < LoginRateLimit.MAX_PER_SOURCE; i++) {
            attempt("wrong passphrase").andExpect(status().isUnauthorized());
        }
    }

    @Test
    @DisplayName("answers 429 with how long to wait")
    void refusesWithARetryAfter() throws Exception {
        exhaust();

        attempt("wrong passphrase")
                .andExpect(status().isTooManyRequests())
                // The header is what an HTTP library or proxy understands; the field is what a
                // client already parsing problem+json for every other failure will read.
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.retryAfterSeconds").isNumber())
                .andExpect(jsonPath("$.title").value("Too many sign-in attempts"));
    }

    /**
     * The whole point of the ordering. Once the limit is spent the correct passphrase is refused
     * too — if it were not, the limit would have to check the passphrase to decide, which is the
     * expensive work it exists to avoid doing.
     */
    @Test
    @DisplayName("refuses the correct passphrase too, rather than checking it to find out")
    void evenTheRightPassphraseIsRefused() throws Exception {
        exhaust();

        attempt(PASSPHRASE).andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("issues no token while refusing")
    void issuesNothing() throws Exception {
        exhaust();
        attempt(PASSPHRASE).andExpect(status().isTooManyRequests());

        assertEquals(0, tokens.count(),
                "a refused sign-in must leave nothing behind that could later be presented");
    }

    /**
     * A rollback here would erase the record the limit counts, leaving every response looking
     * correct while an attacker got unlimited attempts. Recording in its own bean and its own
     * transaction is what stops it, and this is the assertion that would notice.
     */
    @Test
    @DisplayName("records each failure despite the request ending in an exception")
    void failuresSurviveTheRefusal() throws Exception {
        attempt("wrong passphrase").andExpect(status().isUnauthorized());
        attempt("wrong passphrase").andExpect(status().isUnauthorized());

        assertEquals(2, attempts.count(),
                "the 401 is thrown after the failure is written, so a rolled-back write would "
                        + "leave the limit counting nothing at all");
    }

    @Test
    @DisplayName("does not count a successful sign-in against the limit")
    void successIsNotCounted() throws Exception {
        for (int i = 0; i < LoginRateLimit.MAX_PER_SOURCE + 5; i++) {
            attempt(PASSPHRASE).andExpect(status().isOk());
        }

        assertEquals(0, attempts.count(),
                "counting successes would eventually lock somebody out for using their account");
    }
}
