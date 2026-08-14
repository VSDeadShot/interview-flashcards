package dev.vsdeadshot.flashcards.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import dev.vsdeadshot.flashcards.data.local.CardEntity;
import dev.vsdeadshot.flashcards.data.local.PendingReviewEntity;
import dev.vsdeadshot.flashcards.data.remote.dto.CardDto;
import dev.vsdeadshot.flashcards.data.remote.dto.ReviewRequestDto;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.Test;

/** The crossing between the wire and the cache, which is where a dropped field would hide. */
public class MappersTest {

    private CardDto dto() {
        CardDto dto = new CardDto();
        dto.id = 12L;
        dto.topicId = 3L;
        dto.front = "What is a deadlock?";
        dto.back = "Four Coffman conditions";
        dto.easeFactor = 2.36d;
        dto.intervalDays = 6;
        dto.repetitions = 2;
        dto.lapses = 1;
        dto.dueDate = LocalDate.of(2026, 8, 3);
        dto.lastReviewedAt = Instant.parse("2026-07-28T19:40:00Z");
        dto.archived = true;
        return dto;
    }

    @Test
    public void aCardCrossesWithItsWholeSchedule() {
        CardEntity entity = Mappers.toEntity(dto());

        assertEquals(12L, entity.id);
        assertEquals(3L, entity.topicId);
        assertEquals(2.36d, entity.easeFactor, 1e-9);
        assertEquals(6, entity.intervalDays);
        assertEquals(2, entity.repetitions);
        assertEquals(1, entity.lapses);
        assertEquals(LocalDate.of(2026, 8, 3), entity.dueDate);
        assertEquals(Instant.parse("2026-07-28T19:40:00Z"), entity.lastReviewedAt);
        assertTrue("archived cards are cached, not dropped — that flag is how the client tells "
                + "a retired card from one it simply missed", entity.archived);
    }

    @Test
    public void aCardThatWasNeverReviewedStaysThatWay() {
        CardDto dto = dto();
        dto.lastReviewedAt = null;

        assertNull(Mappers.toEntity(dto).lastReviewedAt);
    }

    @Test
    public void anOutboxRowSendsOnlyWhatTheServerNeeds() {
        PendingReviewEntity queued = new PendingReviewEntity();
        queued.id = 44L;
        queued.cardId = 12L;
        queued.confidence = 4;
        queued.reviewedAt = Instant.parse("2026-03-17T09:00:00Z");
        queued.clientReviewId = UUID.randomUUID();
        queued.attempts = 3;
        queued.lastError = "connection reset";

        ReviewRequestDto request = Mappers.toRequest(queued);

        assertEquals(4, request.confidence);
        assertEquals("the day the user actually answered, not the day it reached the server",
                queued.reviewedAt, request.reviewedAt);
        assertEquals("every retry carries the same key, which is what makes it safe",
                queued.clientReviewId, request.clientReviewId);
    }
}
