package dev.vsdeadshot.flashcards.data;

import dev.vsdeadshot.flashcards.data.local.CardEntity;
import dev.vsdeadshot.flashcards.data.local.PendingReviewEntity;
import dev.vsdeadshot.flashcards.data.local.TopicEntity;
import dev.vsdeadshot.flashcards.data.remote.dto.CardDto;
import dev.vsdeadshot.flashcards.data.remote.dto.CardRequestDto;
import dev.vsdeadshot.flashcards.data.remote.dto.ReviewRequestDto;
import dev.vsdeadshot.flashcards.data.remote.dto.TopicDto;
import java.util.ArrayList;
import java.util.List;

/**
 * The seam between what the server says and what the cache keeps.
 *
 * <p>It sits above both packages so that neither imports the other: {@code remote} does not
 * know there is a database, {@code local} does not know there is a server, and the only place
 * a field crosses is here. A DTO field the cache does not store is then a line missing from a
 * visible list rather than a column nobody thought to add.
 */
public final class Mappers {

    private Mappers() {
    }

    public static CardEntity toEntity(CardDto dto) {
        CardEntity entity = new CardEntity();
        // The server's id serves as the local id too for a card that arrived from a pull: it is
        // already unique here and nothing else claims it. serverId is what the sync matches on,
        // and setting it is what marks this row as a cache of something rather than the only
        // copy of it.
        entity.id = dto.id;
        entity.serverId = dto.id;
        entity.topicId = dto.topicId;
        entity.front = dto.front;
        entity.back = dto.back;
        entity.easeFactor = dto.easeFactor;
        entity.intervalDays = dto.intervalDays;
        entity.repetitions = dto.repetitions;
        entity.lapses = dto.lapses;
        entity.dueDate = dto.dueDate;
        entity.lastReviewedAt = dto.lastReviewedAt;
        entity.archived = dto.archived;
        entity.clientCardId = dto.clientCardId;
        return entity;
    }

    /**
     * A plain loop rather than a stream: {@code Stream.toList} is API 34 and this module runs
     * from 26, so the pretty version compiles and then throws {@code NoSuchMethodError} on
     * every device older than the one it was written on. Lint catches it; the loop means there
     * is nothing to catch.
     */
    public static List<CardEntity> toCardEntities(List<CardDto> dtos) {
        List<CardEntity> entities = new ArrayList<>(dtos.size());
        for (CardDto dto : dtos) {
            entities.add(toEntity(dto));
        }
        return entities;
    }

    /**
     * The body of the create for a card written on this device.
     *
     * <p>The key travels with the card rather than being generated here, which is what makes the
     * request safe to repeat: every attempt carries the value stored on the row.
     */
    public static CardRequestDto toCreateRequest(CardEntity card) {
        return new CardRequestDto(card.topicId, card.front, card.back, card.clientCardId);
    }

    /**
     * The body of the update for a card whose local copy has moved on.
     *
     * <p>No key: {@code PUT} replaces, so it is already safe to repeat, and the contract says
     * clients should not send one.
     */
    public static CardRequestDto toUpdateRequest(CardEntity card) {
        return new CardRequestDto(card.topicId, card.front, card.back, null);
    }

    public static TopicEntity toEntity(TopicDto dto) {
        TopicEntity entity = new TopicEntity();
        entity.id = dto.id;
        entity.name = dto.name;
        entity.slug = dto.slug;
        entity.createdAt = dto.createdAt;
        return entity;
    }

    public static List<TopicEntity> toTopicEntities(List<TopicDto> dtos) {
        List<TopicEntity> entities = new ArrayList<>(dtos.size());
        for (TopicDto dto : dtos) {
            entities.add(toEntity(dto));
        }
        return entities;
    }

    /**
     * An outbox row as the request that empties it. The row keeps its own id and its failure
     * count, neither of which is the server's business; what crosses is the answer, when it was
     * given, and the key that makes sending it twice safe.
     */
    public static ReviewRequestDto toRequest(PendingReviewEntity review) {
        return new ReviewRequestDto(review.confidence, review.reviewedAt, review.clientReviewId);
    }
}
