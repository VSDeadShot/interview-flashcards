package dev.vsdeadshot.flashcards.repository;

import dev.vsdeadshot.flashcards.domain.AuthToken;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthTokenRepository extends JpaRepository<AuthToken, Long> {

    /**
     * The one query on the authenticated hot path.
     *
     * <p>Unlike every other finder in this package it does not take a {@code userId}, and that
     * is not an oversight: this is what *establishes* the caller's identity, so there is no
     * owner yet to filter by. The digest is unique, and whose token it is comes back on the row.
     */
    Optional<AuthToken> findByTokenHash(String tokenHash);

    /**
     * Every token in one rotation chain, which is what revoking a family walks.
     *
     * <p>Returns entities rather than issuing a bulk update, so each row goes through
     * {@code AuthToken.revoke} and keeps the rule that the first revocation is the one recorded.
     * A chain is a handful of rows -- two per refresh -- so there is nothing to gain from
     * bypassing the entity and a correctness property to lose.
     */
    List<AuthToken> findByFamilyId(UUID familyId);
}
