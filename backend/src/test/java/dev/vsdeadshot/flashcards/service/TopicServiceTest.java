package dev.vsdeadshot.flashcards.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vsdeadshot.flashcards.domain.Topic;
import dev.vsdeadshot.flashcards.support.EmbeddedPostgresTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class TopicServiceTest extends EmbeddedPostgresTest {

    private static final String USER = "vedansh";
    private static final String OTHER_USER = "someone-else";

    @Autowired
    private TopicService service;

    @Nested
    @DisplayName("creating a topic")
    class Create {

        @Test
        @DisplayName("derives the slug from the name and keeps the name as typed")
        void derivesTheSlug() {
            Topic created = service.create(USER, "Operating Systems");

            assertNotNull(created.getId(), "the identity id is available straight after save");
            assertEquals("Operating Systems", created.getName(), "the display name is not slugified");
            assertEquals("operating-systems", created.getSlug());
            assertNotNull(created.getCreatedAt(), "@PrePersist populates this without a re-read");
        }

        @Test
        @DisplayName("trims surrounding whitespace from the stored name")
        void trimsTheName() {
            assertEquals("DBMS", service.create(USER, "   DBMS  ").getName());
        }

        @Test
        @DisplayName("rejects a name that contains nothing a slug can be built from")
        void rejectsAnUnsluggableName() {
            assertThrows(IllegalArgumentException.class, () -> service.create(USER, "!!!"),
                    "a name of pure punctuation is a 400, not a topic with an empty slug");
        }

        @Test
        @DisplayName("refuses a second topic whose name slugifies the same way")
        void refusesADuplicateSlug() {
            service.create(USER, "Operating Systems");

            // Different names, same slug — which is exactly the collision the check exists for.
            DuplicateTopicException thrown = assertThrows(DuplicateTopicException.class,
                    () -> service.create(USER, "operating systems!"));
            assertEquals("operating-systems", thrown.getSlug(),
                    "the error names the slug that clashed, not the name that was sent");
        }

        @Test
        @DisplayName("lets a different user reuse the same slug")
        void slugsAreUniquePerUserOnly() {
            service.create(USER, "Operating Systems");

            Topic theirs = service.create(OTHER_USER, "Operating Systems");

            assertEquals("operating-systems", theirs.getSlug(),
                    "uniqueness is per user, so this is not a collision");
        }
    }

    @Nested
    @DisplayName("reading topics")
    class Read {

        @Test
        @DisplayName("lists only the requesting user's topics, alphabetically by name")
        void listsOwnTopicsByName() {
            service.create(USER, "Operating Systems");
            service.create(USER, "Databases");
            service.create(OTHER_USER, "Networks");

            List<String> names = service.list(USER).stream().map(Topic::getName).toList();

            assertEquals(List.of("Databases", "Operating Systems"), names);
        }

        @Test
        @DisplayName("finds a topic the user owns")
        void findsOwnTopic() {
            Topic created = service.create(USER, "Operating Systems");

            assertEquals(created.getId(), service.get(USER, created.getId()).getId());
        }

        @Test
        @DisplayName("reports another user's topic as not found rather than forbidden")
        void hidesAnotherUsersTopic() {
            Topic mine = service.create(USER, "Operating Systems");

            NotFoundException thrown =
                    assertThrows(NotFoundException.class, () -> service.get(OTHER_USER, mine.getId()));
            assertTrue(thrown.getMessage().contains("not found"),
                    "answering 403 here would confirm the id exists");
        }

        @Test
        @DisplayName("reports an id that exists for nobody as not found")
        void reportsAnUnknownId() {
            assertThrows(NotFoundException.class, () -> service.get(USER, 999_999L));
        }
    }
}
