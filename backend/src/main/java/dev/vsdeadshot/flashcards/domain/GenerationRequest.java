package dev.vsdeadshot.flashcards.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

/**
 * One row per call to the card generator that was allowed to proceed.
 *
 * <p>Append-only, like {@link ReviewLog}: written once, never updated, never deleted. It exists
 * so the daily cap has something to count, and so "what did generation cost today" can be
 * answered without keeping any of the prompt.
 *
 * <p>Deliberately holds no topic and no text. The cap does not need them, and the fronts sent
 * upstream are the user's own content — copying them into a second table to police a rate limit
 * would be a worse trade than the limit is worth.
 */
@Entity
@Table(name = "generation_request")
public class GenerationRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "cards_requested", nullable = false)
    private int cardsRequested;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected GenerationRequest() {
        // for JPA
    }

    public GenerationRequest(String userId, int cardsRequested, Instant createdAt) {
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        if (cardsRequested <= 0) {
            throw new IllegalArgumentException(
                    "cardsRequested must be greater than zero, was " + cardsRequested);
        }
        this.cardsRequested = cardsRequested;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public Long getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public int getCardsRequested() {
        return cardsRequested;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof GenerationRequest request && id != null && id.equals(request.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(GenerationRequest.class);
    }

    @Override
    public String toString() {
        return "GenerationRequest{id=" + id + ", cardsRequested=" + cardsRequested
                + ", createdAt=" + createdAt + "}";
    }
}
