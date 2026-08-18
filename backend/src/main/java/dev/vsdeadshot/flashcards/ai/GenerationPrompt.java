package dev.vsdeadshot.flashcards.ai;

import java.util.List;

/**
 * Everything the model needs, already assembled.
 *
 * <p>The avoid-list arrives as plain strings so this package never has to know what a Card is —
 * the same reason {@code scheduler/} takes a {@code SchedulingState} rather than an entity.
 */
public record GenerationPrompt(String topicName, String focus, List<String> avoid, int count) {
}
