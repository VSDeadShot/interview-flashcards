package dev.vsdeadshot.flashcards.service;

import java.util.Locale;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Tells one integrity violation from another.
 *
 * <p>Spring translates every constraint failure — unique, check, not-null, foreign key — into
 * one {@link DataIntegrityViolationException}. A service that catches it and assumes which
 * one happened is right only for as long as its table has a single constraint reachable from
 * that statement, and wrong silently afterwards: the caller is told about a duplicate that was
 * really a check violation, and nothing points at the code that decided so.
 *
 * <p>So a catch here names the constraint it is prepared to handle, and anything else is
 * rethrown to become a {@code 500} — the honest answer for a rule the caller could not have
 * known about.
 */
final class Constraints {

    private Constraints() {
    }

    /**
     * Whether {@code failure} was caused by the named database constraint.
     *
     * <p>The name is read from Hibernate's own exception rather than matched against the
     * message text, which varies by driver and would quietly stop working after an upgrade.
     * Spring's translation wraps it, so this walks the chain to find it.
     */
    static boolean isViolationOf(String constraintName, DataIntegrityViolationException failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof ConstraintViolationException violation) {
                String actual = violation.getConstraintName();
                return actual != null
                        && actual.toLowerCase(Locale.ROOT).contains(constraintName.toLowerCase(Locale.ROOT));
            }
        }
        return false;
    }
}
