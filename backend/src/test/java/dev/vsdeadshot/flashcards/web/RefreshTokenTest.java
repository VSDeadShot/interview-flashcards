package dev.vsdeadshot.flashcards.web;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.vsdeadshot.flashcards.repository.AuthTokenRepository;
import dev.vsdeadshot.flashcards.support.EmbeddedPostgresTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
 * Rotation, reuse detection, and signing out.
 *
 * <p>Driven end to end through HTTP rather than against {@code TokenService}, because the
 * behaviour worth pinning is what a client can actually observe: which of the values it holds
 * still work after each step.
 */
@AutoConfigureMockMvc
@DisplayName("Refresh tokens")
class RefreshTokenTest extends EmbeddedPostgresTest {

    private static final String PASSPHRASE = "a passphrase only this test knows";
    private static final String HASH = new BCryptPasswordEncoder(4).encode(PASSPHRASE);
    private static final String ROUTE = "/api/v1/topics";

    @DynamicPropertySource
    static void passphrase(DynamicPropertyRegistry registry) {
        registry.add("flashcards.passphrase-hash", () -> HASH);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AuthTokenRepository tokens;

    @AfterEach
    void clearTokens() {
        tokens.deleteAll();
    }

    /** A signed-in session: the access token and the refresh token it came with. */
    private record Session(String access, String refresh) {
    }

    private Session signIn() throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"passphrase\":\"" + PASSPHRASE + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new Session(JsonPath.read(body, "$.accessToken"),
                JsonPath.read(body, "$.refreshToken"));
    }

    private Session refresh(String refreshToken) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new Session(JsonPath.read(body, "$.accessToken"),
                JsonPath.read(body, "$.refreshToken"));
    }

    private void assertAuthenticates(String accessToken) throws Exception {
        mvc.perform(get(ROUTE).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk());
    }

    private void assertDoesNotAuthenticate(String accessToken) throws Exception {
        mvc.perform(get(ROUTE).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
    }

    private void assertRefreshRejected(String refreshToken) throws Exception {
        mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Nested
    @DisplayName("exchanging one")
    class Exchanging {

        @Test
        @DisplayName("hands back a new pair, both of them different")
        void issuesANewPair() throws Exception {
            Session first = signIn();
            Session second = refresh(first.refresh());

            assertNotEquals(first.access(), second.access(), "a new access token, not the old one");
            assertNotEquals(first.refresh(), second.refresh(),
                    "and a new refresh token -- reusing the old one would make rotation a no-op "
                            + "and leave a thirty-day credential valid after it had been spent");
            assertAuthenticates(second.access());
        }

        @Test
        @DisplayName("comes back in the same shape signing in does")
        void answersTheSameShape() throws Exception {
            Session session = signIn();

            mvc.perform(post("/api/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"refreshToken\":\"" + session.refresh() + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").isString())
                    .andExpect(jsonPath("$.expiresIn").value(3600))
                    .andExpect(jsonPath("$.refreshToken").isString())
                    // Thirty days. Long only because using it replaces it.
                    .andExpect(jsonPath("$.refreshExpiresIn").value(2592000));
        }

        @Test
        @DisplayName("needs no credential of its own, since the token is the credential")
        void isReachableUnauthenticated() throws Exception {
            // Requiring an access token to refresh would defeat the point: the case that matters
            // is precisely the one where the access token has expired.
            refresh(signIn().refresh());
        }

        @Test
        @DisplayName("refuses an access token presented in a refresh token's place")
        void refusesAnAccessToken() throws Exception {
            // Otherwise an access token would buy a fresh thirty-day window every hour, and its
            // own one-hour life would stop meaning anything.
            assertRefreshRejected(signIn().access());
        }

        @Test
        @DisplayName("refuses a refresh token presented as a bearer credential")
        void aRefreshTokenIsNotABearerCredential() throws Exception {
            // The mirror of the case above. A refresh token is long-lived precisely because it
            // only reaches one endpoint; honouring it here would give it an access token's reach.
            assertDoesNotAuthenticate(signIn().refresh());
        }
    }

    @Nested
    @DisplayName("presented a second time")
    class Reuse {

        /**
         * The case the whole design exists for. Two parties hold a token that was already
         * exchanged — the client that exchanged it, and whoever copied it — and there is no way
         * to tell which is presenting it now. So neither is trusted.
         */
        @Test
        @DisplayName("revokes the whole family rather than trusting either holder")
        void reuseRevokesTheFamily() throws Exception {
            Session first = signIn();
            Session second = refresh(first.refresh());
            assertAuthenticates(second.access());

            assertRefreshRejected(first.refresh());

            assertDoesNotAuthenticate(second.access());
            assertRefreshRejected(second.refresh());
        }

        @Test
        @DisplayName("sends the legitimate client back to the passphrase, which is the point")
        void theLegitimateClientIsAlsoLockedOut() throws Exception {
            Session first = signIn();
            Session second = refresh(first.refresh());
            assertRefreshRejected(first.refresh());

            // Signing out the real client too is the cost of not being able to tell them apart,
            // and it is much the cheaper failure: one passphrase entry against a copied token
            // that would otherwise keep working for a month.
            Session recovered = signIn();
            assertAuthenticates(recovered.access());
            assertDoesNotAuthenticate(second.access());
        }

        @Test
        @DisplayName("leaves a separate session alone")
        void anotherSessionSurvives() throws Exception {
            Session other = signIn();
            Session first = signIn();
            refresh(first.refresh());

            assertRefreshRejected(first.refresh());

            // Families are what scope the blast radius. A compromise on one device is not a
            // reason to sign out another.
            assertAuthenticates(other.access());
        }
    }

    @Nested
    @DisplayName("signing out")
    class LoggingOut {

        @Test
        @DisplayName("ends the session, including the access token still in hand")
        void endsTheSession() throws Exception {
            Session session = signIn();

            mvc.perform(post("/api/v1/auth/logout")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"refreshToken\":\"" + session.refresh() + "\"}"))
                    .andExpect(status().isNoContent());

            // Revoking the family rather than the one row is what stops an hour of valid access
            // outliving the sign-out that was meant to end it.
            assertDoesNotAuthenticate(session.access());
            assertRefreshRejected(session.refresh());
        }

        /** The decision this commit records: family-scoped, not user-scoped. */
        @Test
        @DisplayName("ends only the session it was given, not every session the user has")
        void leavesOtherSessionsSignedIn() throws Exception {
            Session phone = signIn();
            Session laptop = signIn();

            mvc.perform(post("/api/v1/auth/logout")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"refreshToken\":\"" + phone.refresh() + "\"}"))
                    .andExpect(status().isNoContent());

            assertDoesNotAuthenticate(phone.access());
            assertAuthenticates(laptop.access());
        }

        @Test
        @DisplayName("answers the same for a token it does not recognise")
        void isSilentAboutAnUnknownToken() throws Exception {
            // A 404 here would tell somebody probing whether a value they hold is real, and a
            // client signing out has nothing to do differently either way.
            mvc.perform(post("/api/v1/auth/logout")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"refreshToken\":\"never-issued-by-anyone\"}"))
                    .andExpect(status().isNoContent());
        }
    }
}
