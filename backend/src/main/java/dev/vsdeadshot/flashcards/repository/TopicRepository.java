package dev.vsdeadshot.flashcards.repository;

import dev.vsdeadshot.flashcards.domain.Topic;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * The per-topic breakdown behind {@code GET /stats}, in one query rather than a count per
     * topic.
     *
     * <p>A {@code left join} so a topic with no cards still appears, with zeros. A topic that
     * vanished from the stats screen the moment its last card was archived would look like a
     * topic that had been deleted, and nothing deletes topics.
     *
     * <p>{@code count(c.id)} rather than {@code count(*)}, which would count the topic row
     * itself and report 1 for an empty topic. {@code count(case when ... end)} counts only the
     * rows that match, since {@code count} ignores nulls.
     *
     * <p>The join repeats the ownership filter even though a card's topic already belongs to
     * the caller. It costs nothing and means the query cannot be made to cross users by a bug
     * somewhere else.
     */
    @Query("""
            select t.id as topicId,
                   t.name as name,
                   count(c.id) as total,
                   count(case when c.dueDate <= :today then 1 end) as due
            from Topic t
            left join Card c
              on c.topic = t and c.userId = t.userId and c.archived = false
            where t.userId = :userId
            group by t.id, t.name
            order by t.name asc
            """)
    List<TopicCardCounts> findTopicStats(
            @Param("userId") String userId, @Param("today") LocalDate today);
}
