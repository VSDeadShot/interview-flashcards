package dev.vsdeadshot.flashcards.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code POST /cards/generate}.
 *
 * @param topicId which of the caller's topics to generate for. Required: a card cannot exist
 *                without a topic, so choosing one up front is one decision rather than one per
 *                candidate.
 * @param focus   an optional narrowing within the topic, such as "normalization". Capped at the
 *                edge because an over-long one would otherwise be sent upstream and billed for
 *                before anything rejected it.
 * @param count   how many to ask for, or null for the default. Deliberately carries no
 *                {@code @Min}/{@code @Max}: {@code CardGenerator} already answers 400 naming the
 *                offending value, and a Bean Validation annotation would intercept a layer
 *                earlier and replace that with "Invalid request content." Same reasoning as
 *                {@link ReviewRequest}'s confidence.
 */
public record GenerateRequest(
        @NotNull Long topicId,
        @Size(max = 200) String focus,
        Integer count) {
}
