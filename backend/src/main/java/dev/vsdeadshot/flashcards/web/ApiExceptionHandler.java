package dev.vsdeadshot.flashcards.web;

import dev.vsdeadshot.flashcards.service.AuthenticationFailedException;
import dev.vsdeadshot.flashcards.service.ConcurrentRequestException;
import dev.vsdeadshot.flashcards.service.DuplicateTopicException;
import dev.vsdeadshot.flashcards.service.GenerationLimitExceededException;
import dev.vsdeadshot.flashcards.service.IdempotencyKeyReuseException;
import dev.vsdeadshot.flashcards.service.LoginLimitExceededException;
import dev.vsdeadshot.flashcards.service.NotFoundException;
import dev.vsdeadshot.flashcards.service.SignInNotConfiguredException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import dev.vsdeadshot.flashcards.ai.GenerationRefusedException;
import dev.vsdeadshot.flashcards.ai.GenerationUnavailableException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns the service layer's exceptions into the {@code application/problem+json} bodies the
 * API contract promises.
 *
 * <p>This exists so controllers can call a service and return its result, with no error
 * handling of their own. A controller that caught these itself would have to agree with
 * every other controller on the status code and the shape of the body; here there is one
 * answer per failure.
 *
 * <p>Spring's own exceptions — a malformed body, a missing parameter, a failed {@code @Valid}
 * — are handled separately by the framework, which {@code spring.mvc.problemdetails.enabled}
 * switches into the same format. That handler is ordered ahead of this one and covers a
 * disjoint set of exceptions, so the two do not compete.
 *
 * <p>Nothing here maps {@link Exception}. An unexpected failure is a bug, and the honest
 * response to one is an empty {@code 500}; a catch-all that copied {@code getMessage()} into
 * {@code detail} would publish constraint names, SQL, and file paths to whoever provoked it.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /**
     * Also the answer for a row owned by somebody else — see {@link NotFoundException}. The
     * status is deliberately indistinguishable from a genuinely absent id.
     */
    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail handleNotFound(NotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Not found", e.getMessage());
    }

    @ExceptionHandler(DuplicateTopicException.class)
    public ProblemDetail handleDuplicateTopic(DuplicateTopicException e) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "Duplicate topic", e.getMessage());
        // The slug is what actually collided, and the caller never sent it — it was derived
        // from the name. Handing it back as a field means a client can react to the conflict
        // without parsing the sentence.
        problem.setProperty("slug", e.getSlug());
        return problem;
    }

    /**
     * Both of these are conflicts, and a client has to tell them apart to know what to do: one
     * is worth retrying and the other never will be. Rather than make it read the title, the
     * answer is a {@code retryable} field — the same reason {@link DuplicateTopicException}
     * hands back the slug instead of expecting the sentence to be parsed.
     */
    @ExceptionHandler(ConcurrentRequestException.class)
    public ProblemDetail handleConcurrentRequest(ConcurrentRequestException e) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "Concurrent request", e.getMessage());
        problem.setProperty("retryable", true);
        return problem;
    }

    @ExceptionHandler(IdempotencyKeyReuseException.class)
    public ProblemDetail handleKeyReuse(IdempotencyKeyReuseException e) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "Idempotency key reused", e.getMessage());
        // A client that retries this gets it again for as long as the key exists, which is
        // forever. Saying so is what stops an outbox retrying it forever.
        problem.setProperty("retryable", false);
        return problem;
    }

    /**
     * The services throw this for input they can reject on sight: a blank front, a confidence
     * outside 1–5, a name that slugifies to nothing.
     *
     * <p>Mapping the JDK's own exception rather than a bespoke one is a deliberate trade. It
     * means an {@code IllegalArgumentException} thrown by a library, for reasons that have
     * nothing to do with the request, would also be reported as the caller's fault. The
     * alternative is a wrapper type at every throw site, and the services are the only code
     * between the controller and the database — they raise this for bad input and nothing
     * else. Revisit if that stops being true.
     */
    /**
     * A rate limit, an upstream outage, a timeout, or no key configured. All of these are the same
     * thing to a caller — not your fault, try again shortly — so they arrive as one exception and
     * leave as one status.
     */
    @ExceptionHandler(GenerationUnavailableException.class)
    public ProblemDetail handleGenerationUnavailable(GenerationUnavailableException e) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Generation unavailable", e.getMessage());
    }

    /**
     * Distinct from unavailable deliberately: the generator answered, so an identical retry
     * produces the same nothing and inviting one would be a lie.
     *
     * <p>{@code GenerationMisconfiguredException} is pointedly absent. Our own credential being
     * rejected is not the caller's to fix and nothing about it should reach them, so it falls
     * through to the unmapped 500 that carries no detail.
     */
    @ExceptionHandler(GenerationRefusedException.class)
    public ProblemDetail handleGenerationRefused(GenerationRefusedException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Generation refused", e.getMessage());
    }

    /**
     * The only place this API answers {@code 429}. Generation is also the only thing it does
     * that costs money per call, which is why it is the only thing rationed.
     *
     * <p>Returns a {@link ResponseEntity} rather than a bare {@link ProblemDetail} so the answer
     * can carry {@code Retry-After}. The same figure is repeated in the body: the header is what
     * a proxy or an HTTP library understands, the field is what a client that already parses
     * {@code problem+json} for every other failure will actually read.
     */
    @ExceptionHandler(GenerationLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleGenerationLimit(GenerationLimitExceededException e) {
        ProblemDetail problem =
                problem(HttpStatus.TOO_MANY_REQUESTS, "Generation limit reached", e.getMessage());
        problem.setProperty("limit", e.getLimit());
        // Named in seconds rather than as a timestamp, because the question is how long to wait
        // and a client would otherwise have to trust its own clock against the server's.
        problem.setProperty("retryAfterSeconds", e.getRetryAfterSeconds());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()))
                .body(problem);
    }

    /**
     * A bodyless {@code 401}, matching what {@code ApiKeyFilter} and {@code AuthTokenFilter}
     * return. Those two answer from inside a filter, where no handler runs and there is nothing
     * to serialise; this one could return a problem body and deliberately does not, so a client
     * has one shape to recognise for "you are not authenticated" rather than two.
     *
     * <p>There is also nothing worth putting in it. Naming the failure would distinguish a wrong
     * passphrase from a well-formed request that was refused for some other reason, which is
     * precisely the distinction somebody guessing would like drawn for them.
     */
    @ExceptionHandler(AuthenticationFailedException.class)
    public ResponseEntity<Void> handleAuthenticationFailure(AuthenticationFailedException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    /**
     * {@code 503} rather than {@code 401}, because the caller's passphrase was never consulted.
     * Telling somebody their credentials were rejected when the server has none configured
     * sends them to look for a fault that is on this side.
     */
    /**
     * The second place this API answers {@code 429}, and unlike generation's it is not about
     * money -- it is what stops an unauthenticated endpoint that deliberately runs bcrypt being
     * used to search for a passphrase or to spend a small instance's CPU.
     *
     * <p>Says how long to wait and pointedly not how many attempts remain. A caller that is
     * guessing would use the second to know exactly how hard it may push; one that is not has
     * no use for it.
     */
    @ExceptionHandler(LoginLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleLoginLimit(LoginLimitExceededException e) {
        ProblemDetail problem = problem(HttpStatus.TOO_MANY_REQUESTS,
                "Too many sign-in attempts", e.getMessage());
        problem.setProperty("retryAfterSeconds", e.getRetryAfterSeconds());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()))
                .body(problem);
    }

    @ExceptionHandler(SignInNotConfiguredException.class)
    public ProblemDetail handleSignInNotConfigured(SignInNotConfiguredException e) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Sign-in unavailable", e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleValidationFailure(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, "Validation failed", e.getMessage());
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
