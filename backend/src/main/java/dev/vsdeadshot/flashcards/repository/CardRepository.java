package dev.vsdeadshot.flashcards.repository;

import dev.vsdeadshot.flashcards.domain.Card;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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

    /**
     * The card a previous attempt at this request already created, if there was one.
     *
     * <p>Scoped by user like every other finder here, which also means one client's key can
     * never collide with another's.
     */
    Optional<Card> findByUserIdAndClientCardId(String userId, UUID clientCardId);

    /** Cards in circulation. Archived ones are not counted — they are out of the app. */
    long countByUserIdAndArchivedFalse(String userId);

    long countByUserIdAndArchivedFalseAndDueDateLessThanEqual(String userId, LocalDate today);

    /**
     * Whether anything was due on one past day — the question that decides whether a day with
     * no reviews breaks the streak or is skipped over.
     *
     * <p>It cannot be answered from {@code due_date} alone. That column holds where a card
     * stands <em>now</em>; a card due last Tuesday and reviewed on Wednesday has had its due
     * date moved on, and Tuesday would read as empty. So the due date as it stood at the start
     * of the day is reconstructed: the last review strictly before that day sets it to that
     * review's date plus {@code interval_after}, and a card with no review before that day is
     * still sitting on the due date it was created with. Adding whole days to the review
     * instant lands inside the day the card became due, which is why this compares instants
     * rather than casting to dates and inheriting the database session's timezone.
     *
     * <p>Native because JPQL cannot express {@code order by ... limit 1} in a subquery, and
     * "the last review before this day" is exactly that.
     *
     * <p>This reads {@code review_log}, which is allowed: the log exists for statistics. The
     * rule it must not break is computing a <em>schedule</em> from it, and nothing here does —
     * the answer is a boolean about the past, and no card is written.
     *
     * <p>Two deliberate exclusions. Archived cards never count, because there is no record of
     * when a card was archived and a retired card should not be able to break a streak
     * retroactively. Cards created during the day itself do not count either — the card had to
     * exist at the start of the day to have been missed, so writing new cards late in the
     * evening cannot turn that evening into a failure.
     */
    @Query(value = """
            select exists (
                select 1
                  from card c
                 where c.user_id = :userId
                   and c.archived = false
                   and c.created_at < :dayStart
                   and coalesce(
                         (select r.reviewed_at + r.interval_after * interval '1 day'
                            from review_log r
                           where r.card_id = c.id
                             and r.reviewed_at < :dayStart
                           order by r.reviewed_at desc
                           limit 1),
                         c.created_at) < :dayEnd)
            """, nativeQuery = true)
    boolean existsCardDueOn(
            @Param("userId") String userId,
            @Param("dayStart") Instant dayStart,
            @Param("dayEnd") Instant dayEnd);
}
