package dev.vsdeadshot.flashcards.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vsdeadshot.flashcards.domain.Topic;
import dev.vsdeadshot.flashcards.support.EmbeddedPostgresTest;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class TopicRepositoryTest extends EmbeddedPostgresTest {

    private static final String USER = "vedansh";
    private static final String OTHER_USER = "someone-else";

    @Autowired
    private EntityManager em;

    @Autowired
    private TopicRepository topics;

    private Topic mine;

    @BeforeEach
    void seed() {
        // Same slug under both users: the constraint is per user, and so are the lookups.
        mine = new Topic(USER, "Operating Systems", "operating-systems");
        em.persist(mine);
        em.persist(new Topic(USER, "Databases", "dbms"));
        em.persist(new Topic(OTHER_USER, "Operating Systems", "operating-systems"));
        em.flush();
    }

    @Test
    @DisplayName("lists only the requesting user's topics, alphabetically")
    void listsOwnTopicsByName() {
        List<String> names = topics.findByUserIdOrderByNameAsc(USER).stream().map(Topic::getName).toList();

        assertEquals(List.of("Databases", "Operating Systems"), names);
    }

    @Test
    @DisplayName("resolves a slug within one user, not across all of them")
    void resolvesSlugPerUser() {
        Topic found = topics.findByUserIdAndSlug(USER, "operating-systems").orElseThrow();

        assertEquals(mine.getId(), found.getId(),
                "the other user's identically slugged topic must not be returned");
    }

    @Test
    @DisplayName("refuses a topic belonging to another user rather than returning it")
    void refusesAnotherUsersTopic() {
        assertTrue(topics.findByIdAndUserId(mine.getId(), OTHER_USER).isEmpty());
    }
}
