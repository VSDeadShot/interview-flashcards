package dev.vsdeadshot.flashcards.service;

import dev.vsdeadshot.flashcards.domain.LoginAttempt;
import dev.vsdeadshot.flashcards.repository.LoginAttemptRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * How often sign-in may be attempted, and from where.
 *
 * <p>{@code POST /auth/login} is the only route an unauthenticated caller can put a body into,
 * and it runs bcrypt, which is expensive by design. That makes it two surfaces at once: somewhere
 * to guess a passphrase, and somewhere to spend a small instance's CPU. Both are answered by
 * refusing to do the work past a threshold — so the check runs <em>before</em> the hash, or the
 * cost this is meant to bound has already been paid.
 *
 * <p><strong>Its own bean rather than a method on {@code AuthController}</strong>, and for a
 * transactional reason rather than a tidiness one — the same reason {@code GenerationQuota} is
 * separate. Recording a failure and then refusing the request means the refusal is an exception
 * thrown after the write; in one transaction that rollback would erase the very record the limit
 * counts, and an attacker would get unlimited attempts while every response still looked correct.
 * Here the write commits in its own transaction before the caller ever throws.
 *
 * <p><strong>Failures only.</strong> A client that signs in successfully has proved it is the
 * owner; counting its successes would eventually lock somebody out of their own account for
 * using it.
 */
@Service
public class LoginRateLimit {

    /** Rolling, not calendar. A fixed window hands out double the allowance across its boundary. */
    static final Duration WINDOW = Duration.ofMinutes(15);

    /**
     * Ten failures from one address in a quarter of an hour. Generous for somebody mistyping a
     * passphrase they chose, and nowhere near enough to search for one.
     *
     * <p>Public because it is a documented fact about the API, unlike {@link #MAX_GLOBAL} below,
     * which is deliberately neither documented nor reported -- a caller told what the backstop
     * is has been told exactly how much room it has before tripping it.
     */
    public static final int MAX_PER_SOURCE = 10;

    /**
     * The backstop, and the honest part of this design. The per-source count keys on an address
     * that a caller behind a proxy can influence, so anybody able to vary a forwarded-for header
     * can present a fresh source per attempt and never trip it. This one cannot be evaded that
     * way because it counts everything.
     *
     * <p>Set high enough that only a real attack reaches it. The cost when it does trip is that
     * the owner cannot sign in either — acceptable because signing in is rare once a refresh
     * token exists, and much cheaper than an unbounded guessing surface.
     */
    static final int MAX_GLOBAL = 100;

    private final LoginAttemptRepository attempts;
    private final Clock clock;

    public LoginRateLimit(LoginAttemptRepository attempts, Clock clock) {
        this.attempts = attempts;
        this.clock = clock;
    }

    /**
     * Refuses the attempt if either limit is already spent.
     *
     * <p>Read-only: reaching a limit is not itself a failure to record. Counting refusals would
     * let a caller extend its own lockout indefinitely by continuing to knock, which turns a
     * limit into a permanent denial.
     *
     * @throws LoginLimitExceededException naming how long to wait
     */
    @Transactional(readOnly = true)
    public void check(String source) {
        Instant now = clock.instant();
        Instant from = now.minus(WINDOW);

        if (attempts.countBySourceAndCreatedAtGreaterThanEqual(source, from) >= MAX_PER_SOURCE) {
            throw new LoginLimitExceededException(waitFrom(
                    attempts.findFirstBySourceAndCreatedAtGreaterThanEqualOrderByCreatedAtAsc(
                            source, from), now));
        }

        if (attempts.countByCreatedAtGreaterThanEqual(from) >= MAX_GLOBAL) {
            throw new LoginLimitExceededException(waitFrom(
                    attempts.findFirstByCreatedAtGreaterThanEqualOrderByCreatedAtAsc(from), now));
        }
    }

    /** Records a sign-in that presented the wrong passphrase. */
    @Transactional
    public void recordFailure(String source) {
        attempts.save(new LoginAttempt(source, clock.instant()));
    }

    /**
     * How long until the oldest failure in the window ages out, which is when the next attempt
     * becomes possible. Never zero: a wait a caller can act on immediately would invite a retry
     * that is still refused.
     */
    private static long waitFrom(Optional<LoginAttempt> oldest, Instant now) {
        return oldest
                .map(attempt -> Duration.between(now, attempt.getCreatedAt().plus(WINDOW))
                        .toSeconds())
                .filter(seconds -> seconds > 0)
                .orElse(WINDOW.toSeconds());
    }
}
