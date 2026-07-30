package dev.vsdeadshot.flashcards.domain;

import dev.vsdeadshot.flashcards.scheduler.SchedulingState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

/**
 * One row per review, written after the card's schedule has been updated.
 *
 * <p>Append-only: never updated, never deleted, and — importantly — <b>never read to
 * compute a schedule</b>. The next interval comes from the card's own columns. This
 * table exists so stats and streaks can be reported without replaying anything.
 *
 * <p>Both sides of each transition are stored so a single row explains itself.
 */
@Entity
@Table(name = "review_log")
public class ReviewLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "card_id", nullable = false)
    private Card card;

    @Column(name = "reviewed_at", nullable = false)
    private Instant reviewedAt;

    @Column(nullable = false)
    private int confidence;

    @Column(name = "interval_before", nullable = false)
    private int intervalBefore;

    @Column(name = "interval_after", nullable = false)
    private int intervalAfter;

    @Column(name = "ease_factor_before", nullable = false)
    private double easeFactorBefore;

    @Column(name = "ease_factor_after", nullable = false)
    private double easeFactorAfter;

    @Column(name = "repetitions_after", nullable = false)
    private int repetitionsAfter;

    protected ReviewLog() {
        // Required by JPA.
    }

    /**
     * Records the transition {@code before -> after} for {@code card}.
     *
     * <p>Taking both states rather than reading the card avoids an ordering trap: by the
     * time this is called the card has usually already been updated, so its columns hold
     * the "after" values and the "before" would be lost.
     */
    public static ReviewLog of(
            Card card, int confidence, SchedulingState before, SchedulingState after, Instant reviewedAt) {
        Objects.requireNonNull(card, "card must not be null");
        Objects.requireNonNull(before, "before must not be null");
        Objects.requireNonNull(after, "after must not be null");

        ReviewLog log = new ReviewLog();
        log.userId = card.getUserId();
        log.card = card;
        log.confidence = confidence;
        log.reviewedAt = Objects.requireNonNull(reviewedAt, "reviewedAt must not be null");
        log.intervalBefore = before.intervalDays();
        log.intervalAfter = after.intervalDays();
        log.easeFactorBefore = before.easeFactor();
        log.easeFactorAfter = after.easeFactor();
        log.repetitionsAfter = after.repetitions();
        return log;
    }

    public Long getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public Card getCard() {
        return card;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public int getConfidence() {
        return confidence;
    }

    public int getIntervalBefore() {
        return intervalBefore;
    }

    public int getIntervalAfter() {
        return intervalAfter;
    }

    public double getEaseFactorBefore() {
        return easeFactorBefore;
    }

    public double getEaseFactorAfter() {
        return easeFactorAfter;
    }

    public int getRepetitionsAfter() {
        return repetitionsAfter;
    }

    /** True when this review was a lapse, derived rather than stored as a fourth column. */
    public boolean isLapse() {
        return repetitionsAfter == 0;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof ReviewLog log && id != null && id.equals(log.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(ReviewLog.class);
    }

    @Override
    public String toString() {
        return "ReviewLog{id=" + id + ", confidence=" + confidence + ", reviewedAt=" + reviewedAt + "}";
    }
}
