package dev.vsdeadshot.flashcards.data.remote.dto;

import java.time.Instant;

/**
 * A topic exactly as {@code GET /topics} returns it.
 *
 * <p>Kept separate from {@code TopicEntity} for the reason the backend keeps {@code web/dto}
 * separate from its entities: the wire format must not become a consequence of the local
 * schema. A field the server sends and the cache does not keep is then a visible decision in
 * the mapper rather than a column nobody added.
 *
 * <p>Fields are public and non-final because Moshi populates them reflectively. Request bodies,
 * which this side builds, are final and take a constructor.
 */
public class TopicDto {

    public long id;
    public String name;
    public String slug;
    public Instant createdAt;
}
