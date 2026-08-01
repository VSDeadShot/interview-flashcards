package dev.vsdeadshot.flashcards.service;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Turns a display name into the url-safe slug stored alongside it.
 *
 * <p>Pure and static, in the same spirit as the scheduler: no clock, no database, no Spring,
 * so its edge cases can be tested without a context.
 */
public final class Slugs {

    /** Matches {@code topic.slug}, so a slug can never be too long for its column. */
    public static final int MAX_LENGTH = 120;

    private Slugs() {
    }

    /**
     * Lowercases, strips accents, and reduces every other run of characters to a single
     * hyphen. Returns an empty string when nothing usable survives — {@code "!!!"} and
     * {@code "…"} are legitimate names to type and illegitimate slugs, so the caller decides
     * what to do about it rather than this returning something invented.
     */
    public static String slugify(String name) {
        if (name == null) {
            return "";
        }
        // NFD splits an accented character into its base plus a combining mark, so removing
        // the marks leaves "Deadlocks & Sémaphores" as "deadlocks-semaphores" rather than
        // dropping the whole letter.
        String withoutAccents = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");

        // Locale.ROOT, not the default locale: under a Turkish locale "I".toLowerCase()
        // produces a dotless i, which would make slugs depend on where the server runs.
        String slug = withoutAccents.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");

        if (slug.length() > MAX_LENGTH) {
            // Trim the hyphen a cut can leave stranded on the end.
            slug = slug.substring(0, MAX_LENGTH).replaceAll("-+$", "");
        }
        return slug;
    }
}
