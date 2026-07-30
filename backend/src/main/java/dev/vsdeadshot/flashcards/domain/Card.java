package dev.vsdeadshot.flashcards.domain;

import dev.vsdeadshot.flashcards.scheduler.SchedulingState;
import dev.vsdeadshot.flashcards.scheduler.Sm2Scheduler;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A single question/answer pair together with its spaced-repetition schedule.
 *
 * <p>The scheduling columns are flattened onto this table because a card and its
 * schedule are strictly 1:1. The arithmetic itself lives in {@link Sm2Scheduler}, which
 * knows nothing about JPA — {@link #schedulingState()} and
 * {@link #applySchedule(SchedulingState, Instant)} are the only bridge between the two.
 */
@Entity
@Table(name = "card")
public class Card {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Size(max = 64)
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    /**
     * Lazy because listing cards rarely needs the topic row itself — the id is enough,
     * and {@code open-in-view} is off, so an accidental fetch outside a transaction
     * fails loudly rather than firing a silent extra query per row.
     */
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "topic_id", nullable = false)
    private Topic topic;

    @NotBlank
    @Column(nullable = false, columnDefinition = "text")
    private String front;

    @NotBlank
    @Column(nullable = false, columnDefinition = "text")
    private String back;

    @Column(name = "ease_factor", nullable = false)
    private double easeFactor = Sm2Scheduler.INITIAL_EASE_FACTOR;

    @Column(name = "interval_days", nullable = false)
    private int intervalDays;

    @Column(nullable = false)
    private int repetitions;

    @Column(nullable = false)
    private int lapses;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    /** Null until the first review. */
    @Column(name = "last_reviewed_at")
    private Instant lastReviewedAt;

    @Column(nullable = false)
    private boolean archived;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Card() {
        // Required by JPA.
    }

    /**
     * Creates an unreviewed card due on {@code today}, so a card added now shows up in
     * today's queue rather than tomorrow's.
     */
    public Card(String userId, Topic topic, String front, String back, LocalDate today) {
        this.userId = userId;
        this.topic = topic;
        this.front = front;
        this.back = back;
        SchedulingState initial = SchedulingState.newCard(today);
        this.easeFactor = initial.easeFactor();
        this.intervalDays = initial.intervalDays();
        this.repetitions = initial.repetitions();
        this.lapses = initial.lapses();
        this.dueDate = initial.dueDate();
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /** The scheduling columns as the value object {@link Sm2Scheduler} operates on. */
    public SchedulingState schedulingState() {
        return new SchedulingState(easeFactor, intervalDays, repetitions, lapses, dueDate);
    }

    /**
     * Writes a scheduler result back onto this card.
     *
     * @param next       the state returned by {@link Sm2Scheduler#schedule}
     * @param reviewedAt when the review that produced {@code next} happened
     */
    public void applySchedule(SchedulingState next, Instant reviewedAt) {
        Objects.requireNonNull(next, "next must not be null");
        this.easeFactor = next.easeFactor();
        this.intervalDays = next.intervalDays();
        this.repetitions = next.repetitions();
        this.lapses = next.lapses();
        this.dueDate = next.dueDate();
        this.lastReviewedAt = Objects.requireNonNull(reviewedAt, "reviewedAt must not be null");
    }

    public Long getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public Topic getTopic() {
        return topic;
    }

    public void setTopic(Topic topic) {
        this.topic = topic;
    }

    public String getFront() {
        return front;
    }

    public void setFront(String front) {
        this.front = front;
    }

    public String getBack() {
        return back;
    }

    public void setBack(String back) {
        this.back = back;
    }

    public double getEaseFactor() {
        return easeFactor;
    }

    public int getIntervalDays() {
        return intervalDays;
    }

    public int getRepetitions() {
        return repetitions;
    }

    public int getLapses() {
        return lapses;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public Instant getLastReviewedAt() {
        return lastReviewedAt;
    }

    public boolean isArchived() {
        return archived;
    }

    /** {@code DELETE /cards/{id}} archives rather than removing, so history survives. */
    public void archive() {
        this.archived = true;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof Card card && id != null && id.equals(card.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(Card.class);
    }

    @Override
    public String toString() {
        return "Card{id=" + id + ", dueDate=" + dueDate + ", repetitions=" + repetitions + "}";
    }
}
