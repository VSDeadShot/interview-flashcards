package dev.vsdeadshot.flashcards.repository;

import dev.vsdeadshot.flashcards.domain.LoginAttempt;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoginAttemptRepository extends JpaRepository<LoginAttempt, Long> {

    /** Failures from one source inside the window. The limit that matters in normal operation. */
    long countBySourceAndCreatedAtGreaterThanEqual(String source, Instant from);

    /**
     * Failures from everywhere inside the window.
     *
     * <p>The backstop. A caller that can set its own forwarded-for header can present a new
     * source per attempt and walk straight past the count above, so a limit that only counted
     * per source would offer assurance it does not actually provide.
     */
    long countByCreatedAtGreaterThanEqual(Instant from);

    /**
     * The oldest failure still inside the window, which is the one whose ageing out frees the
     * next attempt. A fixed calendar window would be simpler and would also hand an attacker
     * double the allowance across every boundary.
     */
    Optional<LoginAttempt> findFirstBySourceAndCreatedAtGreaterThanEqualOrderByCreatedAtAsc(
            String source, Instant from);

    Optional<LoginAttempt> findFirstByCreatedAtGreaterThanEqualOrderByCreatedAtAsc(Instant from);
}
