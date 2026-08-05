package dev.vsdeadshot.flashcards.web;

import dev.vsdeadshot.flashcards.service.Stats;
import dev.vsdeadshot.flashcards.service.StatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /stats}. One route, no parameters — the endpoint answers a single question and
 * everything it needs to answer it is the caller's identity.
 *
 * <p>{@link Stats} goes back as-is rather than through a DTO in {@code web/dto}, for the
 * reason recorded on that record: it is a read model built from counts, not an entity, so
 * there is nothing to hide and no mapping to insulate the wire format from.
 */
@RestController
@RequestMapping("/api/v1/stats")
public class StatsController {

    private final StatsService stats;

    public StatsController(StatsService stats) {
        this.stats = stats;
    }

    @GetMapping
    public Stats stats(@RequestAttribute(ApiKeyFilter.USER_ID_ATTRIBUTE) String userId) {
        return stats.forUser(userId);
    }
}
