package dev.vsdeadshot.flashcards.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Application configuration read from the environment.
 *
 * @param apiKey   the single shared secret every request must present. Like the database
 *                 password it has no fallback: a blank one fails startup rather than leaving
 *                 the API open or trusting a default that ends up in a repository somewhere.
 * @param userId   the owner every row is written under while there is one user. Auth resolves
 *                 to a constant today, but the value still travels through the same seam a
 *                 real subject claim will, so nothing downstream has to change later.
 * @param timezone the zone every day boundary is computed in — the study queue, the streak's
 *                 days, and the generation allowance's reset. Bound as a {@link ZoneId} rather
 *                 than a string so an unknown name fails startup at binding, where it names the
 *                 property, instead of at the first request that asks what day it is. See
 *                 {@link ClockConfiguration#clock} for why this is configured rather than
 *                 taken from the host.
 */
@Validated
@ConfigurationProperties(prefix = "flashcards")
public record FlashcardsProperties(
        @NotBlank String apiKey, @NotBlank String userId, @NotNull ZoneId timezone) {
}
