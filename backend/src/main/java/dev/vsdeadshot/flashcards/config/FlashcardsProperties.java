package dev.vsdeadshot.flashcards.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Application configuration read from the environment.
 *
 * @param userId   the owner every row is written under while there is one user. Auth resolves
 *                 to a constant today, but the value still travels through the same seam a
 *                 real subject claim will, so nothing downstream has to change later.
 * @param timezone the zone every day boundary is computed in — the study queue, the streak's
 *                 days, and the generation allowance's reset. Bound as a {@link ZoneId} rather
 *                 than a string so an unknown name fails startup at binding, where it names the
 *                 property, instead of at the first request that asks what day it is. See
 *                 {@link ClockConfiguration#clock} for why this is configured rather than
 *                 taken from the host.
 * @param passphraseHash the bcrypt hash of the passphrase {@code POST /auth/login} checks.
 *                 <strong>Required, as of the change that removed the API key.</strong> It was
 *                 optional while the key still authenticated every route and signing in was a
 *                 capability rather than a precondition; it is now the only way to obtain the
 *                 only credential, so an instance without one serves nothing and should say so
 *                 at startup rather than at the first sign-in. Like the database password it
 *                 has no fallback — a default credential that ends up in a repository is worse
 *                 than a refusal to start. The hash is stored, never the passphrase. A value
 *                 that is present but not a well-formed bcrypt hash passes this check and is
 *                 still refused by {@code PassphraseAuthenticator}, which is the difference
 *                 between "somebody set something" and "sign-in can work".
 */
@Validated
@ConfigurationProperties(prefix = "flashcards")
public record FlashcardsProperties(
        @NotBlank String userId,
        @NotNull ZoneId timezone,
        @NotBlank String passphraseHash) {

    /**
     * Whether a value was supplied at all — not whether it is usable.
     *
     * <p>The distinction matters and was learned the hard way. A hash mangled on its way into
     * configuration is still non-blank, so a check that stopped here would report sign-in as
     * working and then refuse every correct passphrase with {@code 401} — a server
     * misconfiguration blamed on the caller, and indistinguishable from a wrong passphrase from
     * the outside. Whether the value is a usable bcrypt hash is
     * {@code PassphraseAuthenticator}'s to say, since that is the class that owns bcrypt.
     */
    public boolean hasPassphraseHash() {
        return passphraseHash != null && !passphraseHash.isBlank();
    }
}
