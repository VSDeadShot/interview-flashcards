package dev.vsdeadshot.flashcards.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vsdeadshot.flashcards.domain.Topic;
import dev.vsdeadshot.flashcards.repository.TopicRepository;
import dev.vsdeadshot.flashcards.support.EmbeddedPostgresTest;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proves the constraint name actually survives Spring's exception translation, rather than
 * assuming it does. If Hibernate ever stops reporting it, every narrow catch in the services
 * silently widens back into the catch-all this exists to replace — so the mechanism is pinned
 * against a real violation from a real PostgreSQL server, not a constructed exception.
 */
@Transactional
class ConstraintsTest extends EmbeddedPostgresTest {

    private static final String USER = "vedansh";

    @Autowired
    private TopicRepository topics;

    private DataIntegrityViolationException duplicateSlug() {
        topics.save(new Topic(USER, "Operating Systems", "operating-systems", Instant.now()));
        return assertThrows(DataIntegrityViolationException.class,
                () -> topics.saveAndFlush(
                        new Topic(USER, "Duplicate", "operating-systems", Instant.now())));
    }

    @Test
    @DisplayName("recognises the constraint that actually failed")
    void recognisesTheFailedConstraint() {
        assertTrue(Constraints.isViolationOf("uq_topic_user_slug", duplicateSlug()),
                "the unique slug constraint is what a duplicate topic violates");
    }

    @Test
    @DisplayName("does not claim a different constraint failed")
    void doesNotMatchAnotherConstraint() {
        assertFalse(Constraints.isViolationOf("uq_card_client_id", duplicateSlug()),
                "this is what stops one catch from answering for every violation");
    }
}
