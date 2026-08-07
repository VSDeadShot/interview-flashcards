package dev.vsdeadshot.flashcards.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * The body of both {@code POST /cards} and {@code PUT /cards/{id}}.
 *
 * <p>One record rather than a create and an update pair, because {@code PUT} here is a full
 * replacement of exactly the fields {@code POST} sets — the contract gives them the same three.
 * Two identical records could drift apart by accident; this one can only be split on purpose,
 * which is the right moment to do it.
 *
 * <p>Nothing about the schedule is accepted from a client. A card's ease factor and due date
 * are the scheduler's to decide, and letting a request set them would make studying editable.
 *
 * @param topicId the topic must already exist and belong to the caller; the service resolves it
 *                rather than trusting the id, so this cannot move a card into someone else's
 * @param front   {@code @Size} is a guard, not a schema constraint — the column is {@code text}
 *                and takes anything. An unbounded JSON body has no default limit in Boot, so
 *                without a bound a request could hand the database a megabyte of question. The
 *                cap is far above any real flashcard.
 * @param clientCardId the caller's own id for this request, or absent. Only {@code POST} reads
 *                it: a retried create would otherwise leave two cards where the user wrote one,
 *                and the caller would see a successful create both times. {@code PUT} ignores
 *                it, being a replacement and therefore already safe to repeat.
 */
public record CardRequest(
        @NotNull Long topicId,
        @NotBlank @Size(max = 10_000) String front,
        @NotBlank @Size(max = 10_000) String back,
        UUID clientCardId) {
}
