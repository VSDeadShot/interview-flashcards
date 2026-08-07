package dev.vsdeadshot.flashcards.repository;

import dev.vsdeadshot.flashcards.domain.ReviewLog;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Append-only, so the inherited {@code save} is the whole write side and there is no
 * update or delete method by design.
 *
 * <p>The read side arrived with {@code /stats}. Nothing here may ever be used to compute a
 * schedule — the next interval comes from the card's own columns.
 */
public interface ReviewLogRepository extends JpaRepository<ReviewLog, Long> {

    /**
     * How many reviews happened in a half-open window. One method serves both of the questions
     * {@code /stats} asks — how much was studied today, and whether a given past day was
     * studied at all — because they differ only in the window, and a separate {@code exists}
     * would be the same query with its answer thrown away.
     *
     * <p>Half-open on purpose: a day ends where the next one begins, so a review at exactly
     * midnight belongs to one day only and no review can be counted twice.
     */
    @Query("""
            select count(r) from ReviewLog r
            where r.userId = :userId
              and r.reviewedAt >= :from
              and r.reviewedAt < :to
            """)
    long countBetween(
            @Param("userId") String userId,
            @Param("from") Instant from,
            @Param("to") Instant to);

    /**
     * The first review this user ever recorded, which is where the streak walk stops.
     *
     * <p>A streak only ever counts days that were studied, so no day before the first review
     * can add to it — days further back can only be skipped, and skipping them changes
     * nothing. That makes this a correct floor as well as a tight one, and without a floor the
     * forgiving rule would walk backwards forever through days on which nothing was due.
     *
     * <p>Deliberately the first review rather than the oldest card: a review is the only thing
     * that can add to a streak, so nothing before the first one needs walking. Empty when
     * nothing has ever been reviewed, and the streak is then zero.
     */
    @Query("select min(r.reviewedAt) from ReviewLog r where r.userId = :userId")
    Optional<Instant> findEarliestReviewedAt(@Param("userId") String userId);

    /**
     * The review a previous attempt at this request already recorded, if there was one.
     *
     * <p>This is the whole idempotency mechanism on the read side: the log is append-only, so
     * the row that answers this question outlives any retry that could ask it.
     */
    Optional<ReviewLog> findByUserIdAndClientReviewId(String userId, UUID clientReviewId);
}
