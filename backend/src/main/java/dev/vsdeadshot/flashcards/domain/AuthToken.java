package dev.vsdeadshot.flashcards.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

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

    /**
     * Stored as its name rather than its ordinal. An ordinal is a position in a list, so
     * reordering the enum would silently reinterpret every existing row.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private TokenKind kind;

    /** The rotation chain this token belongs to. Revoking one withdraws every token in it. */
    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "rotated_at")
    private Instant rotatedAt;

    protected AuthToken() {
        // for JPA
    }

    public AuthToken(String userId, String tokenHash, TokenKind kind, UUID familyId,
            Instant createdAt, Instant expiresAt) {
        this.kind = Objects.requireNonNull(kind, "kind must not be null");
        this.familyId = Objects.requireNonNull(familyId, "familyId must not be null");
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
        return revokedAt == null && rotatedAt == null && expiresAt.isAfter(now);
    }

    /**
     * Whether this token was already exchanged for its successor.
     *
     * <p>Kept separate from {@link #isUsableAt} because the two answer different questions. A
     * rotated token is unusable, but a *presentation* of one is evidence that two parties hold
     * it -- the client that rotated it and whoever copied it -- and that is the whole basis of
     * reuse detection. Folding it into "unusable" would throw away the distinction between a
     * token that expired and one that was stolen.
     */
    public boolean isRotated() {
        return rotatedAt != null;
    }

    /** Marks this token as exchanged. Like revocation, the first time is the one that counts. */
    public void rotate(Instant when) {
        if (rotatedAt == null) {
            rotatedAt = Objects.requireNonNull(when, "when must not be null");
        }
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

    public TokenKind getKind() {
        return kind;
    }

    public UUID getFamilyId() {
        return familyId;
    }

    public Instant getRotatedAt() {
        return rotatedAt;
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
        return "AuthToken{id=" + id + ", kind=" + kind + ", expiresAt=" + expiresAt
                + ", revokedAt=" + revokedAt + ", rotatedAt=" + rotatedAt + "}";
    }
}
