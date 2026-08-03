package dev.vsdeadshot.flashcards.web.dto;

import dev.vsdeadshot.flashcards.domain.Topic;
import java.time.Instant;

/**
 * A topic as the API returns it.
 *
 * <p>The entity is not serialised directly. Doing that would publish {@code userId} — an
 * ownership column the caller has no business seeing and could not act on anyway — and would
 * make the wire format a consequence of the mapping, so renaming a field would silently break
 * the Android client.
 *
 * @param slug derived from the name and returned rather than kept private, because it is what
 *             a duplicate conflict reports and the client should be able to line the two up
 */
public record TopicResponse(Long id, String name, String slug, Instant createdAt) {

    public static TopicResponse from(Topic topic) {
        return new TopicResponse(
                topic.getId(), topic.getName(), topic.getSlug(), topic.getCreatedAt());
    }
}
