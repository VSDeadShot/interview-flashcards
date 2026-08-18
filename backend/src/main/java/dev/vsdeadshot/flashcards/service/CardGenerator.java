package dev.vsdeadshot.flashcards.service;

import dev.vsdeadshot.flashcards.ai.GeminiClient;
import dev.vsdeadshot.flashcards.ai.GeneratedCard;
import dev.vsdeadshot.flashcards.ai.GenerationPrompt;
import dev.vsdeadshot.flashcards.ai.GenerationRefusedException;
import dev.vsdeadshot.flashcards.domain.Topic;
import dev.vsdeadshot.flashcards.repository.CardRepository;
import dev.vsdeadshot.flashcards.repository.TopicRepository;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles a prompt, asks the generator, and decides what came back is usable.
 *
 * <p>Lives here rather than in {@code ai/} because it needs repositories, and {@code ai/} is kept
 * free of persistence for the same reason {@code scheduler/} is: the dependency runs one way, and
 * the package that talks to the outside world should not also know what a row looks like.
 *
 * <p>Nothing here writes. Generation produces candidates for a person to approve, and a card only
 * exists once they have.
 */
@Service
public class CardGenerator {

    static final int DEFAULT_COUNT = 8;
    static final int MAX_COUNT = 10;

    /** Matches the contract's cap on {@code front} and {@code back}. */
    static final int MAX_FIELD_LENGTH = 10_000;

    /**
     * Enough context for the model to avoid repeating the obvious questions, bounded because the
     * caller pays for this by the token.
     */
    private static final Limit AVOID_LIMIT = Limit.of(50);

    private final TopicRepository topics;
    private final CardRepository cards;
    private final GeminiClient gemini;

    public CardGenerator(TopicRepository topics, CardRepository cards, GeminiClient gemini) {
        this.topics = topics;
        this.cards = cards;
        this.gemini = gemini;
    }

    /**
     * @param count how many to ask for, or null for the default. Clamped above the maximum and
     *              refused at or below zero — the asymmetry the study queue's {@code limit}
     *              already uses, because asking for too many is not a mistake worth refusing and
     *              asking for none is never anything but a bug.
     */
    @Transactional(readOnly = true)
    public List<GeneratedCard> generate(String userId, long topicId, String focus, Integer count) {
        Topic topic = topics.findByIdAndUserId(topicId, userId)
                .orElseThrow(() -> new NotFoundException("topic", topicId));

        int requested = count == null ? DEFAULT_COUNT : count;
        if (requested <= 0) {
            throw new IllegalArgumentException("count must be greater than zero, was " + requested);
        }

        List<String> avoid = cards.findRecentFronts(userId, topicId, AVOID_LIMIT);
        List<GeneratedCard> generated = gemini.generate(new GenerationPrompt(
                topic.getName(), focus, avoid, Math.min(requested, MAX_COUNT)));

        // Dropping beats failing: one malformed candidate should not cost the other nine, and the
        // caller is going to read every one of these before any of them becomes a card anyway.
        List<GeneratedCard> usable = generated.stream().filter(CardGenerator::usable).toList();
        if (usable.isEmpty()) {
            throw new GenerationRefusedException("The card generator returned nothing usable.");
        }
        return usable;
    }

    private static boolean usable(GeneratedCard card) {
        return present(card.front()) && present(card.back());
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank() && value.length() <= MAX_FIELD_LENGTH;
    }
}
