package dev.vsdeadshot.flashcards.ai;

/** One candidate as the model produced it. Not a card: no id, no schedule, nothing persisted. */
public record GeneratedCard(String front, String back) {
}
