package dev.vsdeadshot.flashcards.service;

/**
 * The requested row does not exist <em>for this user</em>.
 *
 * <p>A row owned by somebody else raises this too, not a permission error: answering
 * {@code 403} would confirm the id exists, which is a fact the caller is not entitled to.
 * The web layer maps this to {@code 404}.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String what, Object id) {
        super(what + " " + id + " was not found");
    }
}
