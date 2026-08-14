package dev.vsdeadshot.flashcards.data.remote.dto;

/**
 * An {@code application/problem+json} body, RFC 9457. Every error the backend returns is one of
 * these, including the ones Spring itself produces, so there is one shape to parse rather than
 * two.
 *
 * <p>{@code retryable} and {@code slug} are extension members: present only on the responses
 * that set them, and null everywhere else. {@code retryable} is the field that separates a
 * {@code 409} worth sending again — two requests with one idempotency key raced, and one lost —
 * from a {@code 409} that never will be, where a key was reused for a different review.
 */
public class ProblemDetail {

    public String type;
    public String title;
    public int status;
    public String detail;
    public String instance;

    public Boolean retryable;
    public String slug;
}
