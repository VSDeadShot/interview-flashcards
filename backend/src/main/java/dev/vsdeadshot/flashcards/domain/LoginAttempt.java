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
 * One failed sign-in.
 *
 * <p>Append-only, like {@link ReviewLog} and {@link GenerationRequest}: written once, never
 * updated, never deleted. It exists so the sign-in limit has something to count.
 *
 * <p>Holds no passphrase, not even a hash of one, and no indication of how close a guess was.
 * The count is the entire purpose, and keeping the attempt itself would turn a table that exists
 * to defend a credential into a place to go looking for one.
 */
@Entity
@Table(name = "login_attempt")
public class LoginAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source", nullable = false, length = 64)
    private String source;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LoginAttempt() {
        // for JPA
    }

    public LoginAttempt(String source, Instant createdAt) {
        this.source = Objects.requireNonNull(source, "source must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public Long getId() {
        return id;
    }

    public String getSource() {
        return source;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof LoginAttempt attempt && id != null && id.equals(attempt.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(LoginAttempt.class);
    }

    @Override
    public String toString() {
        return "LoginAttempt{id=" + id + ", createdAt=" + createdAt + "}";
    }
}
