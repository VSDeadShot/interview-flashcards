package dev.vsdeadshot.flashcards.service;

import dev.vsdeadshot.flashcards.config.FlashcardsProperties;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Checks the one passphrase that can obtain a token.
 *
 * <p>Uses {@code spring-security-crypto} — the standalone artifact, deliberately not
 * {@code spring-boot-starter-security}. The starter brings an entire filter chain and its
 * autoconfiguration, all of which would then have to be switched off to leave this application's
 * own filters in charge. This is one jar with no transitive dependencies, and it is here for
 * exactly one thing.
 *
 * <p>Rolling a key-derivation function by hand was the alternative — PBKDF2 is thirty lines of
 * JDK — and is the kind of thing a security review flags on sight. One jar is the cheaper answer.
 */
@Service
public class PassphraseAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(PassphraseAuthenticator.class);

    /**
     * What a bcrypt hash looks like, mirroring the pattern {@code BCryptPasswordEncoder} uses
     * internally so the two cannot disagree about what it will accept.
     *
     * <p>This exists because of a real failure. A hash whose {@code $} sequences were eaten on
     * the way into an environment variable -- which is what a shell or a dotenv parser does to
     * {@code $2a$12$...} -- arrives here as a non-blank string that bcrypt cannot use. Without
     * this check the application reports sign-in as available and then answers {@code 401} to
     * the correct passphrase: a fault on this side, described to the caller as a fault on
     * theirs, and indistinguishable from a genuinely wrong guess.
     */
    private static final Pattern BCRYPT =
            Pattern.compile("^\\$2[ayb]?\\$\\d{2}\\$[./0-9A-Za-z]{53}$");

    /**
     * Built with the default strength, which is not the strength anything is hashed at here.
     * A bcrypt hash carries its own cost factor, so verification uses whatever the stored hash
     * was generated with — {@code PassphraseHashTool} writes them at 12 — and this encoder's
     * setting only ever applies to hashes it produces, which is none.
     */
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    private final FlashcardsProperties properties;

    public PassphraseAuthenticator(FlashcardsProperties properties) {
        this.properties = properties;

        if (properties.hasPassphraseHash() && !configured()) {
            // At startup rather than only at the first attempt. BCryptPasswordEncoder does warn
            // when matches() is handed something unusable, but that is one line per request among
            // ordinary traffic, and by then the caller has already been told their passphrase was
            // wrong. Said once, at boot, it names the mistake while somebody is still looking.
            //
            // The value itself is never logged. It is not a secret in the sense the passphrase
            // is, but printing a credential-shaped string into a log is not a habit worth having.
            log.warn("FLASHCARDS_PASSPHRASE_HASH is set but is not a valid bcrypt hash "
                    + "(expected 60 characters beginning $2a$, $2b$ or $2y$). Sign-in is "
                    + "disabled and /auth/login will answer 503. A common cause is the $ "
                    + "sequences being expanded by a shell or dotenv parser on the way in.");
        }
    }

    /**
     * Whether signing in can actually work — a hash is present <em>and</em> bcrypt can use it.
     *
     * <p>A malformed hash deliberately reads as unconfigured rather than failing startup. The
     * API key still authenticates every route, so refusing to boot over an optional capability
     * would take a working instance down; this is the same posture generation takes without a
     * Gemini key. What it must not do is masquerade as configured, which is the bug this fixes.
     */
    public boolean configured() {
        return properties.hasPassphraseHash()
                && BCRYPT.matcher(properties.passphraseHash()).matches();
    }

    /**
     * Whether this is the passphrase.
     *
     * <p>Bcrypt's own comparison is constant-time over the digest, so this leaks nothing about
     * how much of a guess was right — the same property {@code ApiKeyFilter} gets from
     * {@code MessageDigest.isEqual}, and the reason neither uses {@code String.equals}.
     *
     * <p>The work factor is the point as much as the hashing is: a verification that costs a
     * few hundred milliseconds puts a floor under how fast anybody can guess, independently of
     * whatever limit sits in front of the endpoint.
     */
    public boolean matches(String presented) {
        if (!configured() || presented == null || presented.isEmpty()) {
            // Never handed to the encoder. A null hash is an IllegalArgumentException, and a
            // blank passphrase matching a blank configuration would be an open door.
            return false;
        }
        return encoder.matches(presented, properties.passphraseHash());
    }
}
