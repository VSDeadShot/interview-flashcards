package dev.vsdeadshot.flashcards.data.remote.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * The body of {@code POST /study/{cardId}/review}.
 *
 * <p>Both optional fields are always sent by this client, which is the whole point of the
 * outbox. {@code reviewedAt} is when the user actually answered, so a review synced that
 * evening counts for the day it happened rather than the day the signal came back;
 * {@code clientReviewId} is what makes resending it safe, because a client that queued a
 * review cannot tell a request that failed from one whose response was lost.
 */
public class ReviewRequestDto {

    public final int confidence;
    public final Instant reviewedAt;
    public final UUID clientReviewId;

    public ReviewRequestDto(int confidence, Instant reviewedAt, UUID clientReviewId) {
        this.confidence = confidence;
        this.reviewedAt = reviewedAt;
        this.clientReviewId = clientReviewId;
    }
}
