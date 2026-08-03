package dev.vsdeadshot.flashcards.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code POST /topics}. Only the name is accepted — the slug is derived, and the
 * owner comes from the key that was presented, not from anything the caller can type.
 *
 * @param name {@code @Size} is load-bearing rather than decorative, and its bound matches
 *             {@code topic.name}'s {@code varchar(120)} on purpose. Removing it does not merely
 *             defer the check: Hibernate validates the entity at persist time and throws a
 *             {@code jakarta.validation.ConstraintViolationException}, which nothing maps, so a
 *             name one character too long answers {@code 500} for what is plainly the caller's
 *             mistake. Verified by removing the annotation and watching the test say so.
 */
public record CreateTopicRequest(@NotBlank @Size(max = 120) String name) {
}
