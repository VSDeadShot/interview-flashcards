package dev.vsdeadshot.flashcards.web;

import dev.vsdeadshot.flashcards.service.CardService;
import dev.vsdeadshot.flashcards.web.dto.CardRequest;
import dev.vsdeadshot.flashcards.web.dto.CardResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /cards}. Same shape as {@link TopicController}: translate, delegate, translate back,
 * with errors left to {@link ApiExceptionHandler} and ownership left to {@link CardService}.
 *
 * <p>Cards hang off a topic but are addressed at the top level rather than nested under
 * {@code /topics/{id}/cards}, because {@code topicId} is a filter here and not an identity —
 * the same list is wanted across every topic, and a card can be moved between topics without
 * changing its URL.
 */
@RestController
@RequestMapping("/api/v1/cards")
public class CardController {

    private final CardService cards;

    public CardController(CardService cards) {
        this.cards = cards;
    }

    /**
     * @param topicId         absent means every topic
     * @param includeArchived archived cards are out by default: {@code DELETE} is what puts
     *                        them there, so a client that had to ask to exclude them would show
     *                        deleted cards to anyone who forgot
     */
    @GetMapping
    public List<CardResponse> list(
            @RequestAttribute(ApiKeyFilter.USER_ID_ATTRIBUTE) String userId,
            @RequestParam(required = false) Long topicId,
            @RequestParam(defaultValue = "false") boolean includeArchived) {
        return cards.list(userId, topicId, includeArchived).stream().map(CardResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CardResponse create(
            @RequestAttribute(ApiKeyFilter.USER_ID_ATTRIBUTE) String userId,
            @Valid @RequestBody CardRequest request) {
        return CardResponse.from(
                cards.create(userId, request.topicId(), request.front(), request.back()));
    }

    /** Edits the text and the topic only — a typo fix must not reset the card's schedule. */
    @PutMapping("/{id}")
    public CardResponse update(
            @RequestAttribute(ApiKeyFilter.USER_ID_ATTRIBUTE) String userId,
            @PathVariable long id,
            @Valid @RequestBody CardRequest request) {
        return CardResponse.from(
                cards.update(userId, id, request.front(), request.back(), request.topicId()));
    }

    /**
     * Archives the card; the row and its review history stay. {@code 204} rather than the
     * archived card, because a client that just deleted something has no use for its body, and
     * returning one would invite treating the response as a card still in play.
     *
     * <p>Archiving an already-archived card succeeds again rather than answering {@code 404},
     * which keeps a retried delete from looking like a failure.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @RequestAttribute(ApiKeyFilter.USER_ID_ATTRIBUTE) String userId,
            @PathVariable long id) {
        cards.archive(userId, id);
    }
}
