package dev.vsdeadshot.flashcards.service;

import dev.vsdeadshot.flashcards.domain.GenerationRequest;
import dev.vsdeadshot.flashcards.repository.GenerationRequestRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * How much card generation one owner may ask for in a day.
 *
 * <p>Its own bean rather than a private method on {@link CardGenerator}, for a reason that is
 * about transactions and not about tidiness. Recording an attempt has to commit before the
 * upstream call starts — a self-invoked {@code @Transactional} method is not proxied and would
 * silently run inside the caller's scope, which here would mean holding a database connection
 * for the length of a call that is allowed to take 45 seconds.
 */
@Service
public class GenerationQuota {

    /**
     * Generations, not cards, because a call is the unit that costs money — the difference
     * between asking for three and asking for ten is a rounding error next to the difference
     * between one call and twenty.
     *
     * <p>Twenty batches is around 160 candidate cards in a day, which is far more than anyone
     * triages and still a bill that cannot run away. Set here rather than in configuration
     * because it is a decision, and a decision nobody has had to change is not yet a setting.
     */
    static final int MAX_PER_DAY = 20;

    private final GenerationRequestRepository requests;
    private final Clock clock;

    public GenerationQuota(GenerationRequestRepository requests, Clock clock) {
        this.requests = requests;
        this.clock = clock;
    }

    /**
     * Records one generation against today's allowance, or refuses it.
     *
     * <p>Called before the upstream request rather than after, so an attempt that fails has
     * still been paid for and still counts. Counting successes instead would leave the
     * expensive failure case — a slow call that times out — completely unlimited.
     *
     * <p>The count and the insert are not atomic against each other, so two requests arriving
     * together can both read the same count and both be allowed. That is deliberate: the
     * overshoot is bounded by how many calls are genuinely in flight at once, it costs at most
     * one extra generation, and the alternative is locking a row on the hot path of a feature
     * one person uses by pressing a button. The idempotency keys elsewhere in this codebase are
     * defended with constraints because losing that race corrupts a card; losing this one costs
     * a few cents.
     *
     * @throws GenerationLimitExceededException if today's allowance is already spent
     */
    @Transactional
    public void consume(String userId, int cards) {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, clock.getZone());
        Instant startOfDay = today.atStartOfDay(clock.getZone()).toInstant();

        if (requests.countByUserIdAndCreatedAtGreaterThanEqual(userId, startOfDay) >= MAX_PER_DAY) {
            Instant tomorrow = today.plusDays(1).atStartOfDay(clock.getZone()).toInstant();
            throw new GenerationLimitExceededException(
                    MAX_PER_DAY, Duration.between(now, tomorrow).toSeconds());
        }

        requests.save(new GenerationRequest(userId, cards, now));
    }
}
