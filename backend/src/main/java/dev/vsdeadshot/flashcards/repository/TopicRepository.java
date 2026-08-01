package dev.vsdeadshot.flashcards.repository;

import dev.vsdeadshot.flashcards.domain.Topic;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Every method here takes {@code userId} even though there is currently one user.
 *
 * <p>That is deliberate: the ownership filter lives in the query rather than in a caller's
 * {@code if}, so a multi-user upgrade cannot leave a lookup accidentally unscoped. A plain
 * {@code findById} inherited from {@link JpaRepository} would ignore ownership entirely,
 * which is why {@link #findByIdAndUserId} exists alongside it.
 */
public interface TopicRepository extends JpaRepository<Topic, Long> {

    Optional<Topic> findByIdAndUserId(Long id, String userId);

    List<Topic> findByUserIdOrderByNameAsc(String userId);

    /**
     * Backs the {@code 409} on a duplicate topic. The unique constraint is still the real
     * guarantee — this only lets the API answer before the database has to.
     */
    Optional<Topic> findByUserIdAndSlug(String userId, String slug);
}
