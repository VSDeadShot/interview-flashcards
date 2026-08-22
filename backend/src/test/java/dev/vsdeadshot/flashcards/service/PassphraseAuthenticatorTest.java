package dev.vsdeadshot.flashcards.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vsdeadshot.flashcards.config.FlashcardsProperties;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Which configured values count as a usable passphrase, and which do not.
 *
 * <p>No Spring context: this is a function of one property, and building a database to ask it
 * would say nothing extra. {@code SignInMalformedHashTest} covers what a caller then sees.
 */
@DisplayName("The passphrase check")
class PassphraseAuthenticatorTest {

    private static final String PASSPHRASE = "the passphrase this test uses";

    /** Cost 4 rather than the tool's 12: a hash carries its own factor, so verifying is identical. */
    private static final String VALID_HASH = new BCryptPasswordEncoder(4).encode(PASSPHRASE);

    private static PassphraseAuthenticator with(String hash) {
        return new PassphraseAuthenticator(
                new FlashcardsProperties("key", "user", ZoneId.of("UTC"), hash));
    }

    @Nested
    @DisplayName("with a usable hash")
    class Usable {

        @Test
        @DisplayName("reports sign-in as available and accepts the passphrase")
        void acceptsTheRightPassphrase() {
            PassphraseAuthenticator authenticator = with(VALID_HASH);

            assertTrue(authenticator.configured(), "a well-formed hash configures sign-in");
            assertTrue(authenticator.matches(PASSPHRASE), "and the passphrase behind it works");
            assertFalse(authenticator.matches("something else"), "while another does not");
        }
    }

    @Nested
    @DisplayName("with nothing configured")
    class Absent {

        @Test
        @DisplayName("reports sign-in as unavailable rather than failing every attempt")
        void nullAndBlankAreUnconfigured() {
            assertFalse(with(null).configured(), "no value means the capability is off");
            assertFalse(with("   ").configured(), "and whitespace is no value");
        }

        @Test
        @DisplayName("never hands a missing hash to the encoder")
        void neverCallsTheEncoderWithNothing() {
            // BCryptPasswordEncoder throws on a null hash, so this is the difference between a
            // 503 and a 500 for an instance that simply has not configured sign-in.
            assertFalse(with(null).matches("anything"), "a missing hash matches nothing");
            assertFalse(with(null).matches(null), "including a missing passphrase");
        }
    }

    /**
     * The case this class was written for. Every value here is non-blank, so the old check
     * called all of them configured — and the endpoint then answered {@code 401} to the correct
     * passphrase, blaming the caller for a fault on this side.
     */
    @Nested
    @DisplayName("with a value that is present but unusable")
    class Malformed {

        private void isNotConfigured(String hash, String why) {
            PassphraseAuthenticator authenticator = with(hash);
            assertFalse(authenticator.configured(), why);
            assertFalse(authenticator.matches(PASSPHRASE),
                    "and the correct passphrase must not appear to work either");
        }

        @Test
        @DisplayName("rejects a hash whose dollar sequences were expanded away")
        void rejectsAShellMangledHash() {
            // What a shell or dotenv parser does to $2a$12$IZSE... : the $-prefixed runs are
            // read as variables and vanish. This is the exact failure that prompted the check.
            isNotConfigured("2a12IZSEMboJ/pxoWyeqzxjekOoV9Zi5uE89GCxf/hNSvnqu9UJzAuwbG",
                    "a hash stripped of its $ markers is not a hash");
        }

        @Test
        @DisplayName("rejects a hash that kept the quotes it was pasted with")
        void rejectsAQuotedHash() {
            isNotConfigured("\"" + VALID_HASH + "\"",
                    "quotes saved as part of the value make it 62 characters, not 60");
        }

        @Test
        @DisplayName("rejects a truncated hash")
        void rejectsATruncatedHash() {
            isNotConfigured(VALID_HASH.substring(0, 40), "a clipped hash cannot verify anything");
        }

        @Test
        @DisplayName("rejects a hash carrying stray whitespace")
        void rejectsPaddedHash() {
            isNotConfigured(" " + VALID_HASH, "a leading space is not part of a bcrypt hash");
            isNotConfigured(VALID_HASH + "\n", "and neither is a trailing newline");
        }

        @Test
        @DisplayName("rejects a plaintext passphrase left where the hash belongs")
        void rejectsAPlaintextPassphrase() {
            // Worth its own case: it is the mistake with the worst consequence if it were ever
            // accepted, and it is entirely plausible for somebody to make once.
            isNotConfigured(PASSPHRASE, "a passphrase in the hash's place is not a hash");
        }
    }
}
