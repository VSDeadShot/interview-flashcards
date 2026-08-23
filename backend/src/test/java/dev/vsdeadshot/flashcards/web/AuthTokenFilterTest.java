package dev.vsdeadshot.flashcards.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.vsdeadshot.flashcards.domain.AuthToken;
import dev.vsdeadshot.flashcards.domain.TokenKind;
import dev.vsdeadshot.flashcards.repository.AuthTokenRepository;
import dev.vsdeadshot.flashcards.support.EmbeddedPostgresTest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The only credential this API accepts.
 *
 * <p>This class used to be about two credentials coexisting, and its most important assertion
 * was that adding tokens had not broken the key. The key is gone, and the assertion that
 * replaces it is the inverse: <strong>a request presenting nothing is refused</strong>. While
 * the key existed, a request with no bearer header was simply not addressed to this filter and
 * fell through to be judged elsewhere. With nothing behind it, falling through would mean
 * serving an unauthenticated request — so the absence of a header and a bad one now answer the
 * same way.
 *
 * <p>Aimed at {@code /api/v1/topics}, a route that exists, so a request the filter allows
 * through answers {@code 200} and one it refuses answers {@code 401}. Nothing here depends on
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
    private AuthTokenRepository repository;

    @Autowired
    private Clock clock;

    @AfterEach
    void clearTokens() {
        repository.deleteAll();
    }

    /**
     * Computed here rather than borrowed from {@code TokenService}, so a planted row does not
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
        @DisplayName("authenticates the request")
        void authenticates() throws Exception {
            mvc.perform(get(ROUTE).header(HttpHeaders.AUTHORIZATION, bearer()))
                    .andExpect(status().isOk());
        }
    }

    /**
     * The behaviour this change inverted, and the reason the class is worth reading. Each of
     * these used to fall through to the API key filter; there is nothing behind them now, so
     * each has to be a refusal in its own right rather than a decision deferred.
     */
    @Nested
    @DisplayName("with nothing to authenticate")
    class Missing {

        @Test
        @DisplayName("refuses a request with no Authorization header")
        void refusesAnAbsentHeader() throws Exception {
            mvc.perform(get(ROUTE)).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("refuses an Authorization header that is not a bearer token")
        void refusesAnotherScheme() throws Exception {
            // This used to fall through as "not addressed to this filter", which was right only
            // while something else could still authenticate it.
            mvc.perform(get(ROUTE).header(HttpHeaders.AUTHORIZATION, "Basic abc"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("refuses a bearer header carrying nothing")
        void refusesAnEmptyToken() throws Exception {
            mvc.perform(get(ROUTE).header(HttpHeaders.AUTHORIZATION, "Bearer "))
                    .andExpect(status().isUnauthorized());
        }

        /**
         * The header this API stopped reading. Worth its own case: a client still sending it is
         * the exact situation this change creates, and the answer has to be a plain {@code 401}
         * rather than anything that looks like the key was considered.
         */
        @Test
        @DisplayName("refuses a request still presenting the retired API key header")
        void refusesTheRetiredKeyHeader() throws Exception {
            mvc.perform(get(ROUTE).header("X-API-Key", "whatever-the-old-key-was"))
                    .andExpect(status().isUnauthorized());
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
                    TokenKind.ACCESS, UUID.randomUUID(),
                    now.minus(Duration.ofHours(2)), now.minus(Duration.ofHours(1))));

            mvc.perform(get(ROUTE).header(HttpHeaders.AUTHORIZATION, "Bearer " + PLANTED_TOKEN))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("refuses a token that was revoked while still in date")
        void refusesARevokedToken() throws Exception {
            Instant now = clock.instant();
            AuthToken token = new AuthToken(TEST_USER_ID, digestOf(PLANTED_TOKEN),
                    TokenKind.ACCESS, UUID.randomUUID(), now, now.plus(Duration.ofHours(1)));
            token.revoke(now);
            repository.save(token);

            // This is what makes a lost device recoverable without rebuilding an application,
            // so it has to beat an expiry that has not arrived yet.
            mvc.perform(get(ROUTE).header(HttpHeaders.AUTHORIZATION, "Bearer " + PLANTED_TOKEN))
                    .andExpect(status().isUnauthorized());
        }

        /**
         * A refresh token is long-lived precisely because it only ever reaches one endpoint.
         * Accepting one here would hand a thirty-day credential to every route.
         */
        @Test
        @DisplayName("refuses a refresh token presented as a bearer credential")
        void refusesARefreshTokenAsABearer() throws Exception {
            Instant now = clock.instant();
            repository.save(new AuthToken(TEST_USER_ID, digestOf(PLANTED_TOKEN),
                    TokenKind.REFRESH, UUID.randomUUID(), now, now.plus(Duration.ofDays(30))));

            mvc.perform(get(ROUTE).header(HttpHeaders.AUTHORIZATION, "Bearer " + PLANTED_TOKEN))
                    .andExpect(status().isUnauthorized());
        }
    }
}
