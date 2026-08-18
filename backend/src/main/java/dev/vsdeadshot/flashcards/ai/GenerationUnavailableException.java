package dev.vsdeadshot.flashcards.ai;

/**
 * The generator could not be reached or could not answer right now: a rate limit, an upstream
 * outage, a timeout, or no key configured.
 *
 * <p>All of these mean the same thing to a caller — not your fault, try again shortly — which is
 * why they are one exception and not four.
 */
public class GenerationUnavailableException extends RuntimeException {

    public GenerationUnavailableException(String message) {
        super(message);
    }
}
