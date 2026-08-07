package dev.vsdeadshot.flashcards.web;

import dev.vsdeadshot.flashcards.service.StudyService;
import dev.vsdeadshot.flashcards.web.dto.CardResponse;
import dev.vsdeadshot.flashcards.web.dto.ReviewRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /study} — the two routes the Android client spends its time in. Same shape as
 * {@link CardController}: translate, delegate, translate back.
 *
 * <p>Studying is a verb, not a collection, which is why these do not live under
 * {@code /cards/{id}}. A review is not an edit of a card — it is an event that happens to one,
 * and the card's new schedule is the consequence rather than the request.
 */
@RestController
@RequestMapping("/api/v1/study")
public class StudyController {

    private final StudyService study;

    public StudyController(StudyService study) {
        this.study = study;
    }

    /**
     * Cards due today or earlier, longest overdue first.
     *
     * @param limit the default is written as {@code StudyService}'s own constant rather than a
     *              literal, so the number in the contract cannot drift from the number the
     *              service clamps against. Above {@link StudyService#MAX_LIMIT} it is clamped
     *              rather than refused — asking for too much is not an error, but a limit of
     *              zero or less is nonsense and answers {@code 400}.
     */
    @GetMapping("/queue")
    public List<CardResponse> queue(
            @RequestAttribute(ApiKeyFilter.USER_ID_ATTRIBUTE) String userId,
            @RequestParam(defaultValue = "" + StudyService.DEFAULT_LIMIT) int limit) {
        return study.queue(userId, limit).stream().map(CardResponse::from).toList();
    }

    /**
     * Records the review and answers with the rescheduled card.
     *
     * <p>{@code 200} and the whole card rather than {@code 204}: the new interval, ease factor
     * and due date are the entire point of asking, and the client caches cards by id, so
     * returning the same {@link CardResponse} the queue handed out lets it replace its copy
     * without a second request. An offline client that predicted the schedule locally replaces
     * its prediction with this, which is what keeps the two halves from drifting.
     */
    @PostMapping("/{cardId}/review")
    public CardResponse review(
            @RequestAttribute(ApiKeyFilter.USER_ID_ATTRIBUTE) String userId,
            @PathVariable long cardId,
            @Valid @RequestBody ReviewRequest request) {
        return CardResponse.from(study.review(
                userId, cardId, request.confidence(), request.reviewedAt(), request.clientReviewId()));
    }
}
