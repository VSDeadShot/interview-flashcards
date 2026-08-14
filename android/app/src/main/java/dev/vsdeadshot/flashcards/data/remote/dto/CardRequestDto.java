package dev.vsdeadshot.flashcards.data.remote.dto;

import java.util.UUID;

/**
 * The body of {@code POST /cards} and {@code PUT /cards/{id}} — one shape, as on the server,
 * because the update replaces exactly the fields the create sets.
 */
public class CardRequestDto {

    public final long topicId;
    public final String front;
    public final String back;

    /**
     * Read by {@code POST} only, and null until slice 2 gives this client a way to author
     * cards. {@code PUT} ignores it, being a replacement and so already safe to repeat.
     */
    public final UUID clientCardId;

    public CardRequestDto(long topicId, String front, String back, UUID clientCardId) {
        this.topicId = topicId;
        this.front = front;
        this.back = back;
        this.clientCardId = clientCardId;
    }
}
