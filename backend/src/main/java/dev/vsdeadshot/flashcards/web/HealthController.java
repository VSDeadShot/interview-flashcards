package dev.vsdeadshot.flashcards.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /health}. The liveness probe a hosting platform polls to decide whether this
 * instance is serving.
 *
 * <p><strong>Deliberately outside {@code /api/}</strong>, which is what makes it the one route
 * {@link ApiKeyFilter} does not guard. A probe cannot present a credential, and an authenticated
 * health check answers {@code 401} forever — which a platform reads as a permanently unhealthy
 * instance and restarts on a loop. Any future replacement for that filter inherits the exemption
 * for free, because it is a property of the path rather than an entry on a list somewhere.
 *
 * <p><strong>It does not touch the database, and that is the decision.</strong> A platform's
 * health check is wired to restarting the process, so making it fail on a database outage
 * converts an outage into a restart loop that cannot possibly repair it. What this answers is
 * the question a restart *can* act on: is the process up and dispatching requests. Startup
 * already covers the rest — Flyway migrates and Hibernate validates before the first request is
 * served, so an instance that answers this at all has a schema it agrees with.
 *
 * <p><strong>It says nothing else, because it is public.</strong> No version, no build stamp, no
 * hostname, no dependency status. This is the only unauthenticated route in the application, so
 * anything it returns is returned to anyone who asks; "up" is the whole of what a probe needs.
 * Same reasoning that keeps {@code ApiExceptionHandler} from mapping {@code Exception} and
 * copying {@code getMessage()} into a response.
 */
@RestController
public class HealthController {

    /**
     * A record here rather than in {@code web/dto} — one field, one producer, and no entity
     * behind it that a DTO would be insulating the wire format from. Same exception
     * {@code Stats} already makes.
     */
    public record Health(String status) {
    }

    private static final Health UP = new Health("UP");

    @GetMapping("/health")
    public Health health() {
        return UP;
    }
}
