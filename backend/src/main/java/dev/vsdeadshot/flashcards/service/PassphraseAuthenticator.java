package dev.vsdeadshot.flashcards.service;

import dev.vsdeadshot.flashcards.config.FlashcardsProperties;
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
    }

    public boolean configured() {
        return properties.signInConfigured();
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
