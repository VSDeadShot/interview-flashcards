package dev.vsdeadshot.flashcards;

import dev.vsdeadshot.flashcards.support.EmbeddedPostgresTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FlashcardsApplicationTests extends EmbeddedPostgresTest {

    @Test
    @DisplayName("the context starts, the migrations apply, and the mappings validate")
    void contextLoads() {
        // Deliberately empty. Startup is the assertion: Flyway applies V1 to an empty
        // database and Hibernate validates every entity against the result, so a column
        // rename or a type mismatch fails here.
    }
}
