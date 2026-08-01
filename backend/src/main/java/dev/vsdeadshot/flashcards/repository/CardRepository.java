package dev.vsdeadshot.flashcards.repository;

import dev.vsdeadshot.flashcards.domain.Card;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CardRepository extends JpaRepository<Card, Long> {

    Optional<Card> findByIdAndUserId(Long id, String userId);

    /**
     * The study queue: everything due on or before {@code today}, oldest due first.
     *
     * <p>The {@code archived = false} predicate is written out literally rather than
     * expressed as {@code archived <> true} or filtered in Java, because it has to match
     * {@code idx_card_due}'s own {@code where archived = false} clause verbatim for the
     * planner to recognise the partial index as applicable. This is the hot path — it runs
     * every time the app is opened — so the index is the point of the query, not a bonus.
     *
     * <p>{@code id} breaks ties on {@code dueDate}, otherwise the order of cards due on the
     * same day is whatever the plan happens to produce and paging through the queue could
     * repeat or skip a card.
     *
     * <p>{@code topic} stays lazy: callers need only {@code topic.id}, which a lazy proxy
     * already holds, so no fetch join is needed and none is added.
     */
    @Query("""
            select c from Card c
            where c.userId = :userId
              and c.archived = false
              and c.dueDate <= :today
            order by c.dueDate asc, c.id asc
            """)
    List<Card> findStudyQueue(
            @Param("userId") String userId, @Param("today") LocalDate today, Limit limit);

    /**
     * Backs {@code GET /cards?topicId=&includeArchived=}. Both filters are optional, so they
     * are folded into one query rather than exploding into four derived method names.
     *
     * @param topicId          null means every topic
     * @param includeArchived  false hides archived cards, which is the default the API uses
     */
    @Query("""
            select c from Card c
            where c.userId = :userId
              and (:topicId is null or c.topic.id = :topicId)
              and (:includeArchived = true or c.archived = false)
            order by c.id asc
            """)
    List<Card> findForListing(
            @Param("userId") String userId,
            @Param("topicId") Long topicId,
            @Param("includeArchived") boolean includeArchived);
}
