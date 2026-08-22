package dev.vsdeadshot.flashcards.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.vsdeadshot.flashcards.domain.AuthToken;
import dev.vsdeadshot.flashcards.repository.AuthTokenRepository;
import dev.vsdeadshot.flashcards.service.TokenService;
import dev.vsdeadshot.flashcards.support.EmbeddedPostgresTest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Two credentials, coexisting.
 *
 * <p>The API key is not removed by this change, so the thing most worth pinning is that adding
 * bearer tokens did not quietly break it — an application that only authenticated the new way
 * would strand every client build already out there.
 *
 * <p>Aimed at {@code /api/v1/topics}, a route that exists, so a request the filters allow
 * through answers {@code 200} and one they refuse answers {@code 401}. Nothing here depends on
 * what that endpoint actually returns.
 */
@AutoConfigureMockMvc
@DisplayName("Bearer token authentication")
class AuthTokenFilterTest extends EmbeddedPostgresTest {

    private static final String ROUTE = "/api/v1/topics";

    /** Never issued by the service, so its digest can be stored under any expiry a test wants. */
    private static final String PLANTED_TOKEN = "a-token-planted-by-a-test";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TokenService tokens;

    @Autowired
    private AuthTokenRepository repository;

    @Autowired
    private Clock clock;

    @AfterEach
    void clearTokens() {
        repository.deleteAll();
    }

    private String bearer() {
        return "Bearer " + tokens.issue(TEST_USER_ID).token();
    }

    /**
     * Computed here rather than borrowed from {@link TokenService}, so a planted row does not
     * depend on the class under test to decide what a digest is.
     */
    private static String digestOf(String token) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }

    @Nested
    @DisplayName("with a valid token")
    class Valid {

        @Test
        @DisplayName("authenticates a request carrying no key at all")
        void authenticatesWithoutAKey() throws Exception {
            mvc.perform(get(ROUTE).header(HttpHeaders.AUTHORIZATION, bearer()))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("with the key this change does not remove")
    class OldKey {

        /** The migration's whole promise: nothing that worked before stops working. */
        @Test
        @DisplayName("still authenticates a request presenting only the API key")
        void theKeyStillWorks() throws Exception {
            mvc.perform(get(ROUTE).header(ApiKeyFilter.HEADER, TEST_API_KEY))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("still refuses a request presenting neither")
        void neitherIsStillRefused() throws Exception {
            mvc.perform(get(ROUTE)).andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("with a token that does not authenticate")
    class Rejected {

        @Test
        @DisplayName("refuses a token this server never issued")
        void refusesAnUnknownToken() throws Exception {
            mvc.perform(get(ROUTE).header(HttpHeaders.AUTHORIZATION, "Bearer made-up"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("refuses a token whose expiry has passed")
        void refusesAnExpiredToken() throws Exception {
            Instant now = clock.instant();
            // Planted with an expiry already behind it, since the clock cannot be wound forward
            // and waiting an hour is not a test.
            repository.save(new AuthToken(TEST_USER_ID, digestOf(PLANTED_TOKEN),
                    now.minus(Duration.ofHours(2)), now.minus(Duration.ofHours(1))));

            mvc.perform(get(ROUTE).header(HttpHeaders.AUTHORIZATION, "Bearer " + PLANTED_TOKEN))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("refuses a token that was revoked while still in date")
        void refusesARevokedToken() throws Exception {
            Instant now = clock.instant();
            AuthToken token = new AuthToken(TEST_USER_ID, digestOf(PLANTED_TOKEN),
                    now, now.plus(Duration.ofHours(1)));
            token.revoke(now);
            repository.save(token);

            // This is what makes a lost device recoverable without rebuilding an application,
            // so it has to beat an expiry that has not arrived yet.
            mvc.perform(get(ROUTE).header(HttpHeaders.AUTHORIZATION, "Bearer " + PLANTED_TOKEN))
                    .andExpect(status().isUnauthorized());
        }

        /**
         * A failed token is refused outright rather than passed along to the key filter.
         * Otherwise the answer would depend on which credential happened to be checked first,
         * and a client holding a stale token would be quietly authenticated by a key it also
         * still carried — hiding the expiry this whole design relies on being visible.
         */
        @Test
        @DisplayName("refuses a bad token even when a valid key is presented alongside it")
        void aBadTokenIsNotRescuedByAValidKey() throws Exception {
            mvc.perform(get(ROUTE)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer made-up")
                            .header(ApiKeyFilter.HEADER, TEST_API_KEY))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("ignores an Authorization header that is not a bearer token")
        void ignoresAnotherScheme() throws Exception {
            // Not this filter's to refuse: a Basic header is a request it was not addressed by,
            // so it falls through and the key filter has the final say.
            mvc.perform(get(ROUTE)
                            .header(HttpHeaders.AUTHORIZATION, "Basic abc")
                            .header(ApiKeyFilter.HEADER, TEST_API_KEY))
                    .andExpect(status().isOk());
        }
    }
}
