package dev.vsdeadshot.flashcards.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** No Spring and no database — {@link Slugs} is pure, so its tests are too. */
class SlugsTest {

    @Test
    @DisplayName("lowercases and hyphenates an ordinary topic name")
    void slugifiesAnOrdinaryName() {
        assertEquals("operating-systems", Slugs.slugify("Operating Systems"));
    }

    @Test
    @DisplayName("strips accents down to the base letter rather than dropping it")
    void stripsAccents() {
        assertEquals("deadlocks-semaphores", Slugs.slugify("Deadlocks & Sémaphores"));
    }

    @Test
    @DisplayName("collapses runs of punctuation and trims the ends")
    void collapsesAndTrims() {
        assertEquals("dbms-indexing", Slugs.slugify("  --DBMS!!  Indexing -- "));
    }

    @Test
    @DisplayName("keeps digits, which topic names legitimately contain")
    void keepsDigits() {
        assertEquals("2-phase-locking", Slugs.slugify("2-Phase Locking"));
    }

    @Test
    @DisplayName("returns empty rather than inventing a slug when nothing usable is left")
    void returnsEmptyWhenNothingSurvives() {
        assertEquals("", Slugs.slugify("!!! …"), "the caller decides what to do, this does not guess");
        assertEquals("", Slugs.slugify(""));
        assertEquals("", Slugs.slugify(null));
    }

    @Test
    @DisplayName("truncates to the column length without leaving a trailing hyphen")
    void truncatesCleanly() {
        String slug = Slugs.slugify("a ".repeat(Slugs.MAX_LENGTH));

        assertTrue(slug.length() <= Slugs.MAX_LENGTH, "must fit topic.slug, was " + slug.length());
        assertFalse(slug.endsWith("-"), "a cut must not strand a hyphen on the end, was: " + slug);
    }

    @Test
    @DisplayName("produces the same slug regardless of the default locale")
    void isLocaleIndependent() {
        Locale original = Locale.getDefault();
        try {
            // Turkish is the classic trap: "I".toLowerCase() there is a dotless ı, which would
            // silently make slugs — and therefore the uniqueness constraint — depend on where
            // the server happens to run.
            Locale.setDefault(Locale.forLanguageTag("tr"));

            assertEquals("i-o-scheduling", Slugs.slugify("I/O Scheduling"));
        } finally {
            Locale.setDefault(original);
        }
    }
}
