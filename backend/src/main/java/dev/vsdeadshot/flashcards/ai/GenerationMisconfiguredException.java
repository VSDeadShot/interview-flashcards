package dev.vsdeadshot.flashcards.ai;

/**
 * The generator rejected our credentials.
 *
 * <p>Distinct from {@link GenerationUnavailableException} because it is neither temporary nor the
 * caller's to fix. It is deliberately left unmapped by {@code ApiExceptionHandler}, so it becomes
 * a {@code 500} carrying no detail — the honest answer for a fault on this side of the wire that
 * the caller could not have caused and cannot resolve, and one that leaks nothing about the key.
 */
public class GenerationMisconfiguredException extends RuntimeException {

    public GenerationMisconfiguredException(String message) {
        super(message);
    }
}
