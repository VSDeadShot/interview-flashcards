package dev.vsdeadshot.flashcards.web;

import dev.vsdeadshot.flashcards.service.CardCreation;
import dev.vsdeadshot.flashcards.service.CardGenerator;
import dev.vsdeadshot.flashcards.service.CardService;
import dev.vsdeadshot.flashcards.web.dto.CandidateResponse;
import dev.vsdeadshot.flashcards.web.dto.CardRequest;
import dev.vsdeadshot.flashcards.web.dto.CardResponse;
import dev.vsdeadshot.flashcards.web.dto.GenerateRequest;
import dev.vsdeadshot.flashcards.web.dto.GenerateResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
    private final CardGenerator generator;

    public CardController(CardService cards, CardGenerator generator) {
        this.cards = cards;
        this.generator = generator;
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

    /**
     * {@code 201} for a card this request created, {@code 200} for one an earlier attempt at
     * the same request already made. The body is identical either way, so a client needs no
     * branch — but {@code 201} asserts a creation, and on a replay no creation happened. The
     * status code is the one part of the response that cannot say "already done".
     */
    @PostMapping
    public ResponseEntity<CardResponse> create(
            @RequestAttribute(ApiKeyFilter.USER_ID_ATTRIBUTE) String userId,
            @Valid @RequestBody CardRequest request) {
        CardCreation created = cards.create(
                userId, request.topicId(), request.front(), request.back(), request.clientCardId());
        return ResponseEntity
                .status(created.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(CardResponse.from(created.card()));
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

    /**
     * Generates candidate cards for one of the caller's topics. Nothing is stored: a candidate
     * becomes a card only when the caller posts it back, which is what makes reviewing before
     * saving possible at all.
     *
     * <p>A {@code 200} rather than a {@code 201} for the same reason, and an action-shaped path
     * like {@code POST /study/{cardId}/review} rather than one naming a resource that does not
     * exist.
     */
    @PostMapping("/generate")
    public GenerateResponse generate(
            @RequestAttribute(ApiKeyFilter.USER_ID_ATTRIBUTE) String userId,
            @Valid @RequestBody GenerateRequest request) {
        return new GenerateResponse(
                generator.generate(userId, request.topicId(), request.focus(), request.count())
                        .stream()
                        .map(CandidateResponse::from)
                        .toList());
    }
}
