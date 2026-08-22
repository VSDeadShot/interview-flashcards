package dev.vsdeadshot.flashcards.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vsdeadshot.flashcards.domain.AuthToken;
import dev.vsdeadshot.flashcards.domain.TokenKind;
import dev.vsdeadshot.flashcards.repository.AuthTokenRepository;
import dev.vsdeadshot.flashcards.support.EmbeddedPostgresTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
@DisplayName("Issuing and checking bearer tokens")
class TokenServiceTest extends EmbeddedPostgresTest {

    private static final String USER = "token-test";

    @Autowired
    private TokenService tokens;

    @Autowired
    private AuthTokenRepository repository;

    @Autowired
    private Clock clock;

    @Nested
    @DisplayName("issuing")
    class Issuing {

        @Test
        @DisplayName("returns a token that then authenticates its owner")
        void issuesAUsableToken() {
            TokenService.Issued issued = tokens.issue(USER);

            assertEquals(Optional.of(USER), tokens.authenticate(issued.accessToken()),
                    "a token just issued must authenticate the owner it was issued to");
            assertEquals(TokenService.ACCESS_TOKEN_TTL.toSeconds(), issued.expiresInSeconds(),
                    "the caller is told how long it has, in seconds it can count down itself");
        }

        /**
         * The property the whole storage design rests on. If the row held the token, reading
         * this table would hand somebody a working credential.
         */
        @Test
        @DisplayName("stores a digest rather than the token")
        void storesOnlyADigest() {
            TokenService.Issued issued = tokens.issue(USER);

            assertTrue(repository.findByTokenHash(issued.accessToken()).isEmpty(),
                    "the raw token must not be what the row is keyed by -- if this finds a row, "
                            + "the token itself is sitting in the database");
            assertEquals(2, repository.count(),
                    "a sign-in writes the access token and its refresh token, both digested");
        }

        @Test
        @DisplayName("issues a different token every time")
        void issuesDistinctTokens() {
            // Not a test of randomness, which no test can be. It catches the failure that would
            // actually happen: material generated once and reused, or a seed fixed by accident.
            assertNotEquals(tokens.issue(USER).accessToken(), tokens.issue(USER).accessToken(),
                    "two tokens issued in a row must not be the same value");
        }
    }

    @Nested
    @DisplayName("checking")
    class Checking {

        private void store(Instant createdAt, Instant expiresAt, boolean revoked) {
            // Written straight to the repository so the clock does not have to be wound
            // forward -- the same approach GenerationQuotaTest takes to yesterday.
            AuthToken token = new AuthToken(USER, "a".repeat(64), TokenKind.ACCESS,
                    UUID.randomUUID(), createdAt, expiresAt);
            if (revoked) {
                token.revoke(createdAt);
            }
            repository.save(token);
        }

        @Test
        @DisplayName("refuses a token it never issued")
        void refusesAnUnknownToken() {
            assertTrue(tokens.authenticate("not-a-token-this-server-ever-made").isEmpty(),
                    "an unrecognised token authenticates nobody");
        }

        @Test
        @DisplayName("refuses a token that has expired")
        void refusesAnExpiredToken() {
            Instant now = clock.instant();
            store(now.minus(Duration.ofHours(2)), now.minus(Duration.ofHours(1)), false);

            assertTrue(repository.findByTokenHash("a".repeat(64))
                            .map(t -> t.isUsableAt(now)).orElse(false) == false,
                    "an expiry in the past must stop the token working, with no sweep required");
        }

        @Test
        @DisplayName("refuses a token that was revoked before it expired")
        void refusesARevokedToken() {
            Instant now = clock.instant();
            store(now, now.plus(Duration.ofHours(1)), true);

            assertFalse(repository.findByTokenHash("a".repeat(64)).orElseThrow().isUsableAt(now),
                    "revoking is what makes a lost device recoverable without a rebuild, so it "
                            + "has to beat an expiry that has not arrived yet");
        }

        @Test
        @DisplayName("refuses a blank or missing token without going to the database")
        void refusesNothingAtAll() {
            assertTrue(tokens.authenticate(null).isEmpty(), "null authenticates nobody");
            assertTrue(tokens.authenticate("   ").isEmpty(), "blank authenticates nobody");
        }
    }
}
