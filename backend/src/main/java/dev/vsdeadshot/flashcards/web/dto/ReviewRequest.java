package dev.vsdeadshot.flashcards.web.dto;

import jakarta.validation.constraints.NotNull;

/**
 * The body of {@code POST /study/{cardId}/review}. One field, because a review is one fact:
 * how well the card was recalled. Everything else about the outcome is the scheduler's to work
 * out, and the moment it happened is the server's.
 *
 * @param confidence 1–5. Deliberately <strong>not</strong> annotated with the range, unlike
 *                   {@link CardRequest}'s sizes: {@code StudyService} already rejects an
 *                   out-of-range confidence with the scheduler's own constants, and that
 *                   answers {@code 400} with the offending value in the detail. A
 *                   {@code @Min}/{@code @Max} pair here would intercept the same request one
 *                   layer earlier and reply {@code "Invalid request content."} instead —
 *                   a second copy of the rule, and a worse message for it. The sizes on
 *                   {@code CardRequest} earn their place because without them the request
 *                   reaches Hibernate and becomes a {@code 500}; this one does not.
 *                   <p>Boxed rather than {@code int} so a body that omits the field is null and
 *                   fails {@code @NotNull}, instead of Jackson defaulting it to {@code 0} and
 *                   the request reading as a lapse nobody asked for.
 */
public record ReviewRequest(@NotNull Integer confidence) {
}
