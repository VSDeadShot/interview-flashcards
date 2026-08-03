package dev.vsdeadshot.flashcards.web;

import dev.vsdeadshot.flashcards.service.TopicService;
import dev.vsdeadshot.flashcards.web.dto.CreateTopicRequest;
import dev.vsdeadshot.flashcards.web.dto.TopicResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /topics}.
 *
 * <p>Deliberately thin: translate, delegate, translate back. There is no error handling here
 * because {@link ApiExceptionHandler} owns it, and no ownership check because
 * {@link TopicService} takes the owner as a parameter and filters in the query.
 *
 * <p>{@code userId} arrives as a request attribute set by {@link ApiKeyFilter}, not from
 * configuration. That is the seam: when the shared key becomes a real token, the filter starts
 * publishing a subject claim and nothing in this class changes.
 */
@RestController
@RequestMapping("/api/v1/topics")
public class TopicController {

    private final TopicService topics;

    public TopicController(TopicService topics) {
        this.topics = topics;
    }

    @GetMapping
    public List<TopicResponse> list(
            @RequestAttribute(ApiKeyFilter.USER_ID_ATTRIBUTE) String userId) {
        return topics.list(userId).stream().map(TopicResponse::from).toList();
    }

    /**
     * No {@code Location} header, though {@code 201} conventionally carries one: there is no
     * {@code GET /topics/{id}} in the contract, so the header would point at a route that
     * answers {@code 404}. The body already carries the id.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TopicResponse create(
            @RequestAttribute(ApiKeyFilter.USER_ID_ATTRIBUTE) String userId,
            @Valid @RequestBody CreateTopicRequest request) {
        return TopicResponse.from(topics.create(userId, request.name()));
    }
}
