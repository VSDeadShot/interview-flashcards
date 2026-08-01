package dev.vsdeadshot.flashcards.repository;

import dev.vsdeadshot.flashcards.domain.ReviewLog;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Append-only, so the inherited {@code save} is the whole write side and there is no
 * update or delete method by design.
 *
 * <p>Read methods will be added when {@code /stats} is built. Nothing here may ever be
 * used to compute a schedule — the next interval comes from the card's own columns.
 */
public interface ReviewLogRepository extends JpaRepository<ReviewLog, Long> {
}
