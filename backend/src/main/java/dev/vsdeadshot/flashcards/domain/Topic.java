package dev.vsdeadshot.flashcards.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Objects;

/**
 * A subject area cards are grouped under — "Operating Systems", "DBMS", and so on.
 *
 * <p>{@code slug} is unique per user, not globally, which is what the
 * {@code uq_topic_user_slug} constraint expresses.
 */
@Entity
@Table(name = "topic")
public class Topic {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Size(max = 64)
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @NotBlank
    @Size(max = 120)
    @Column(nullable = false, length = 120)
    private String name;

    @NotBlank
    @Size(max = 120)
    @Column(nullable = false, length = 120)
    private String slug;

    /**
     * Set by {@link #onCreate()} rather than left to the column default, so the value is
     * readable straight after {@code save()} without re-reading the row. The database
     * default stays as a backstop for rows inserted by hand.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Topic() {
        // Required by JPA.
    }

    /**
     * @param createdAt passed in rather than stamped on persist, so that every timestamp the
     *                  application writes comes from the one injected {@code Clock} — and so
     *                  that {@code createdAt}, which the API returns, is the same "now" the
     *                  rest of a request works from
     */
    public Topic(String userId, String name, String slug, Instant createdAt) {
        this.userId = userId;
        this.name = name;
        this.slug = slug;
        this.createdAt = createdAt;
    }

    /** A backstop for a topic persisted without one; the constructor is the normal path. */
    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Two topics are the same only once both have been persisted and share an id. An
     * unsaved entity is equal to nothing but itself, which keeps it well-behaved in a
     * {@code Set} across a {@code save()} call.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof Topic topic && id != null && id.equals(topic.id);
    }

    @Override
    public int hashCode() {
        // Constant on purpose: the id is null before the insert and non-null after, so
        // hashing it would move the entity between buckets mid-session.
        return Objects.hashCode(Topic.class);
    }

    @Override
    public String toString() {
        return "Topic{id=" + id + ", slug='" + slug + "'}";
    }
}
