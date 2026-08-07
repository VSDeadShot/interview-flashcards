package dev.vsdeadshot.flashcards.service;

import dev.vsdeadshot.flashcards.domain.Card;
import dev.vsdeadshot.flashcards.domain.Topic;
import dev.vsdeadshot.flashcards.repository.CardRepository;
import dev.vsdeadshot.flashcards.repository.TopicRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Card reads and writes. Reviewing a card is not here — that belongs with the scheduler in
 * the study service, and this class deliberately never touches a scheduling column.
 *
 * <p>{@link TopicRepository} is injected rather than {@link TopicService} because the only
 * thing needed is the ownership-scoped lookup; depending on another service for one query
 * would add coupling without adding behaviour.
 */
@Service
public class CardService {

    private final CardRepository cards;
    private final TopicRepository topics;
    private final Clock clock;

    public CardService(CardRepository cards, TopicRepository topics, Clock clock) {
        this.cards = cards;
        this.topics = topics;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Card> list(String userId, Long topicId, boolean includeArchived) {
        return cards.findForListing(userId, topicId, includeArchived);
    }

    @Transactional(readOnly = true)
    public Card get(String userId, long id) {
        return cards.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new NotFoundException("card", id));
    }

    @Transactional
    public Card create(String userId, long topicId, String front, String back) {
        Topic topic = requireOwnedTopic(userId, topicId);
        // Due today rather than tomorrow, so a card added during a session can be studied in it.
        return cards.save(new Card(userId, topic, require(front, "front"), require(back, "back"),
                LocalDate.now(clock), clock.instant()));
    }

    /**
     * Replaces the editable fields. The scheduling state is untouched — correcting a typo must
     * not reset a card's progress.
     */
    @Transactional
    public Card update(String userId, long id, String front, String back, long topicId) {
        Card card = get(userId, id);
        // Resolved against the caller, not just by id: without this a card could be moved
        // into a topic belonging to somebody else.
        card.setTopic(requireOwnedTopic(userId, topicId));
        card.setFront(require(front, "front"));
        card.setBack(require(back, "back"));
        return card;
    }

    /**
     * Archives rather than deleting, so the review history the card is referenced by survives.
     * Archiving an already-archived card is a no-op, which keeps {@code DELETE} idempotent.
     */
    @Transactional
    public void archive(String userId, long id) {
        get(userId, id).archive();
    }

    private Topic requireOwnedTopic(String userId, long topicId) {
        return topics.findByIdAndUserId(topicId, userId)
                .orElseThrow(() -> new NotFoundException("topic", topicId));
    }

    /**
     * The entity carries {@code @NotBlank} as well, but that only fires at flush and surfaces
     * as a constraint violation. Checking here turns it into a plain validation failure the
     * web layer can render as {@code 400} with a useful field name.
     */
    private static String require(String value, String field) {
        String trimmed = value == null ? "" : value.strip();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return trimmed;
    }
}
