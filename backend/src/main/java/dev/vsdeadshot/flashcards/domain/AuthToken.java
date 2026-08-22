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
 * One issued bearer token, stored as a digest.
 *
 * <p><strong>The token itself is never held here.</strong> Only its SHA-256, so this table can
 * be read without yielding anything a client could present. That is also why the digest is not
 * a password hash: the token is 32 bytes of {@code SecureRandom}, so there is no low-entropy
 * guess to slow down, and bcrypt here would buy nothing while charging its cost to every
 * authenticated request.
 *
 * <p>Rows are never deleted. A token that was withdrawn early carries {@code revokedAt} rather
 * than disappearing, so what was issued and what became of it stays answerable.
 */
@Entity
@Table(name = "auth_token")
public class AuthToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected AuthToken() {
        // for JPA
    }

    public AuthToken(String userId, String tokenHash, Instant createdAt, Instant expiresAt) {
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash must not be null");
        if (tokenHash.length() != 64) {
            // The one shape error worth refusing outright: a token stored in the clear would
            // look like a working row and would be exactly the thing this design avoids.
            throw new IllegalArgumentException(
                    "tokenHash must be a 64-character SHA-256 hex digest, was length "
                            + tokenHash.length());
        }
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException(
                    "expiresAt must be after createdAt, was " + expiresAt + " and " + createdAt);
        }
    }

    /**
     * Whether this token authenticates a request made at {@code now}.
     *
     * <p>Expiry is compared rather than assumed from a scheduled cleanup, so a token stops
     * working at the moment it should even if nothing has swept the table.
     */
    public boolean isUsableAt(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    public void revoke(Instant when) {
        if (revokedAt == null) {
            // Left alone if already revoked: the first revocation is when it stopped working,
            // and overwriting it with a later one would lose that.
            revokedAt = Objects.requireNonNull(when, "when must not be null");
        }
    }

    public Long getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof AuthToken token && id != null && id.equals(token.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(AuthToken.class);
    }

    /** Deliberately without the digest: a token's identifier has no business in a log line. */
    @Override
    public String toString() {
        return "AuthToken{id=" + id + ", expiresAt=" + expiresAt + ", revokedAt=" + revokedAt + "}";
    }
}
