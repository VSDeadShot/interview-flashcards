package dev.vsdeadshot.flashcards.service;

import dev.vsdeadshot.flashcards.domain.Card;
import dev.vsdeadshot.flashcards.domain.Topic;
import dev.vsdeadshot.flashcards.repository.CardRepository;
import dev.vsdeadshot.flashcards.repository.TopicRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
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

    /** Creates a card with nothing to deduplicate against — the online path. */
    @Transactional
    public Card create(String userId, long topicId, String front, String back) {
        return create(userId, topicId, front, back, null).card();
    }

    /**
     * Creates a card, or returns the one an earlier attempt at the same request already made.
     *
     * <p>A client that queues work offline cannot tell a request that failed from one whose
     * response was lost, so it retries either way. Without a key that turns one card into two,
     * and nothing in the response says so — the caller sees a successful create both times.
     *
     * @param clientCardId the caller's id for this request, or null to skip deduplication
     * @throws ConcurrentRequestException if an identical request is in flight and won the race
     */
    @Transactional
    public CardCreation create(
            String userId, long topicId, String front, String back, UUID clientCardId) {
        if (clientCardId != null) {
            Optional<Card> already = cards.findByUserIdAndClientCardId(userId, clientCardId);
            if (already.isPresent()) {
                // Deliberately not compared against the payload the way a repeated review is.
                // A card is editable, so the row may legitimately no longer resemble the
                // request that created it, and a mismatch would say nothing about the client.
                return new CardCreation(already.get(), true);
            }
        }

        Topic topic = requireOwnedTopic(userId, topicId);
        try {
            // Due today rather than tomorrow, so a card added during a session can be studied in it.
            return new CardCreation(cards.saveAndFlush(new Card(
                    userId, topic, require(front, "front"), require(back, "back"),
                    LocalDate.now(clock), clock.instant(), clientCardId)), false);
        } catch (DataIntegrityViolationException e) {
            // Only reachable when a request with this key committed between the lookup above
            // and this insert. Named rather than assumed, so a future constraint on `card`
            // cannot be reported as a duplicated request.
            if (!Constraints.isViolationOf("uq_card_client_id", e)) {
                throw e;
            }
            throw new ConcurrentRequestException(
                    "a request with clientCardId " + clientCardId + " is already in progress");
        }
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
