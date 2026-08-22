package dev.vsdeadshot.flashcards.repository;

import dev.vsdeadshot.flashcards.domain.AuthToken;
import java.util.Optional;
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
}
