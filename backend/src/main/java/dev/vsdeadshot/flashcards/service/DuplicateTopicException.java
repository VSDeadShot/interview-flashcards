package dev.vsdeadshot.flashcards.service;

/**
 * This user already has a topic with the same slug. The web layer maps this to {@code 409}.
 *
 * <p>Two different names can collide — "Operating Systems" and "operating systems" both
 * slugify to {@code operating-systems} — so the message carries the slug rather than the
 * name the caller sent, which is the part that actually clashed.
 */
public class DuplicateTopicException extends RuntimeException {

    private final String slug;

    public DuplicateTopicException(String slug) {
        super("a topic with the slug '" + slug + "' already exists");
        this.slug = slug;
    }

    public String getSlug() {
        return slug;
    }
}
