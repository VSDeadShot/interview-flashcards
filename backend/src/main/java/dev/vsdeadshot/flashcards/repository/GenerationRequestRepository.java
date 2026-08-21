package dev.vsdeadshot.flashcards.repository;

import dev.vsdeadshot.flashcards.domain.GenerationRequest;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GenerationRequestRepository extends JpaRepository<GenerationRequest, Long> {

    /**
     * How many generations this user has already been allowed since {@code from}.
     *
     * <p>Scoped by {@code userId} like every other finder here, so one user's spending can never
     * be counted against another's cap — which today is a formality with one owner, and stops
     * being one the moment there are two.
     */
    long countByUserIdAndCreatedAtGreaterThanEqual(String userId, Instant from);
}
