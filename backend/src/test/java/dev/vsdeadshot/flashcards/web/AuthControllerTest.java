package dev.vsdeadshot.flashcards.web;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.vsdeadshot.flashcards.repository.AuthTokenRepository;
import dev.vsdeadshot.flashcards.repository.LoginAttemptRepository;
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

/**
 * Signing in, and what the token it hands back is then good for.
 *
 * <p>Not {@code @Transactional}, like the other controller tests, so the rows a sign-in writes
 * are real and are cleaned up by hand.
 */
@AutoConfigureMockMvc
@DisplayName("Signing in")
class AuthControllerTest extends EmbeddedPostgresTest {

    private static final String PASSPHRASE = "a passphrase only this test knows";

    /**
     * Hashed at cost 4 rather than the 12 the tool writes. A bcrypt hash carries its own cost
     * factor, so the server verifies whatever it is given -- which lets the suite pay
     * milliseconds per sign-in instead of half a second, while exercising the identical path.
     */
    private static final String HASH = new BCryptPasswordEncoder(4).encode(PASSPHRASE);

    @DynamicPropertySource
    static void passphrase(DynamicPropertyRegistry registry) {
        registry.add("flashcards.passphrase-hash", () -> HASH);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AuthTokenRepository tokens;

    @Autowired
    private LoginAttemptRepository attempts;

    /**
     * Both tables, not just the tokens. The refused sign-ins below leave rows in
     * {@code login_attempt}, and one embedded Postgres is shared by the whole test JVM -- so
     * leaving them behind spends part of the sign-in allowance for whichever class runs next.
     * That is not hypothetical: it is what this method was extended to fix.
     */
    @AfterEach
    void clearAuthTables() {
        tokens.deleteAll();
        attempts.deleteAll();
    }

    private String login(String passphrase) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"passphrase\":\"" + passphrase + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // JsonPath rather than Jackson: spring-test already brings it for the jsonPath()
        // matchers used throughout this suite, and the object mapper is not on the test
        // compile classpath in its own right.
        return JsonPath.read(body, "$.accessToken");
    }

    @Test
    @DisplayName("hands back a token and how long it lasts")
    void issuesAToken() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"passphrase\":\"" + PASSPHRASE + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.expiresIn").value(3600));
    }

    @Test
    @DisplayName("needs no credential to reach, since it is how a credential is obtained")
    void isReachableUnauthenticated() throws Exception {
        // The assertion is the absence of a 401. Requiring a key to sign in would be a closed
        // loop, and it is PublicRoutes rather than this controller that decides so.
        assertNotNull(login(PASSPHRASE), "sign-in must work with no key and no token presented");
    }

    @Test
    @DisplayName("issues a token that then authenticates an ordinary route")
    void theTokenWorks() throws Exception {
        String token = login(PASSPHRASE);

        mvc.perform(get("/api/v1/topics").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("refuses a wrong passphrase with no body to read")
    void refusesAWrongPassphrase() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"passphrase\":\"not the passphrase\"}"))
                .andExpect(status().isUnauthorized())
                // Same shape the filters answer with, so a client has one thing to recognise
                // rather than two. There is also nothing worth saying: naming the failure would
                // separate a wrong passphrase from every other reason to refuse.
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("writes no token for a sign-in it refused")
    void refusingIssuesNothing() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"passphrase\":\"not the passphrase\"}"))
                .andExpect(status().isUnauthorized());

        org.junit.jupiter.api.Assertions.assertEquals(0, tokens.count(),
                "a refused sign-in must leave nothing behind that could later be presented");
    }

    @Test
    @DisplayName("answers an empty passphrase as a bad request, not a failed sign-in")
    void refusesABlankPassphrase() throws Exception {
        // 400 rather than 401: nothing was attempted, so reporting a rejected credential would
        // describe an attempt that never happened.
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"passphrase\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}
