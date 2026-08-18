package dev.vsdeadshot.flashcards.ai;

/**
 * The generator answered, but nothing usable came back.
 *
 * <p>Deliberately distinct from {@link GenerationUnavailableException}: retrying this identical
 * request will produce the same nothing, so inviting a retry would be a lie.
 */
public class GenerationRefusedException extends RuntimeException {

    public GenerationRefusedException(String message) {
        super(message);
    }
}
