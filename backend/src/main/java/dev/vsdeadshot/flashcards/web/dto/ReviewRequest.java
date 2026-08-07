package dev.vsdeadshot.flashcards.web.dto;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * The body of {@code POST /study/{cardId}/review}: how well the card was recalled, and — for a
 * client that could not say so at the time — when. Everything else about the outcome is the
 * scheduler's to work out.
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
 * @param reviewedAt when the review happened, or absent for "now" — which is what an online
 *                   client sends, and why this is optional rather than required. It exists for
 *                   the offline case: a review done on a train and synced that evening must
 *                   count for the day it happened, or the streak punishes the user for having
 *                   no signal. Bounded by {@code StudyService} rather than annotated here, for
 *                   the same reason {@code confidence} is — the service can say what was wrong
 *                   with the value, and Bean Validation would replace that with
 *                   {@code "Invalid request content."}
 */
public record ReviewRequest(@NotNull Integer confidence, Instant reviewedAt) {
}
