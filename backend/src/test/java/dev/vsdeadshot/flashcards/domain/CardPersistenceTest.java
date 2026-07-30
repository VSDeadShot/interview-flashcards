package dev.vsdeadshot.flashcards.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vsdeadshot.flashcards.scheduler.SchedulingState;
import dev.vsdeadshot.flashcards.scheduler.Sm2Scheduler;
import dev.vsdeadshot.flashcards.support.EmbeddedPostgresTest;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Round-trips the entities through a real PostgreSQL server.
 *
 * <p>{@code ddl-auto=validate} already proves the mappings line up with the migration, but
 * only in shape. These tests prove rows actually survive a write and a read, and that the
 * database enforces the invariants the migration claims to.
 *
 * <p>Every method is transactional and rolls back, so the shared embedded server stays
 * clean between tests.
 */
@Transactional
class CardPersistenceTest extends EmbeddedPostgresTest {

    private static final String USER = "vedansh";
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 30);
    private static final double EPSILON = 1e-9;

    @Autowired
    private EntityManager em;

    private final Sm2Scheduler scheduler = new Sm2Scheduler();

    private Topic persistTopic(String slug) {
        Topic topic = new Topic(USER, "Operating Systems", slug);
        em.persist(topic);
        return topic;
    }

    /** Walks to the deepest cause, which is where the database puts the constraint name. */
    private static String rootMessage(Throwable thrown) {
        Throwable current = thrown;
        StringBuilder all = new StringBuilder();
        while (current != null) {
            all.append(current.getMessage()).append('\n');
            current = current.getCause();
        }
        return all.toString().toLowerCase(Locale.ROOT);
    }

    @Test
    @DisplayName("a new card round-trips with the schedule of an unreviewed card")
    void newCardRoundTrips() {
        Topic topic = persistTopic("operating-systems");
        Card card = new Card(USER, topic, "What is a deadlock?", "Four Coffman conditions.", TODAY);
        em.persist(card);

        em.flush();
        em.clear();

        Card loaded = em.find(Card.class, card.getId());
        assertNotNull(loaded);
        assertEquals("What is a deadlock?", loaded.getFront());
        assertEquals(Sm2Scheduler.INITIAL_EASE_FACTOR, loaded.getEaseFactor(), EPSILON);
        assertEquals(0, loaded.getIntervalDays());
        assertEquals(0, loaded.getRepetitions());
        assertEquals(TODAY, loaded.getDueDate(), "a new card is due today, not tomorrow");
        assertNull(loaded.getLastReviewedAt(), "never reviewed");
        assertNotNull(loaded.getCreatedAt(), "@PrePersist populates this without a re-read");
    }

    @Test
    @DisplayName("a review applied through the scheduler is persisted onto the card")
    void appliedScheduleIsPersisted() {
        Topic topic = persistTopic("dbms");
        Card card = new Card(USER, topic, "What is 2PL?", "Two-phase locking.", TODAY);
        em.persist(card);

        Instant reviewedAt = Instant.parse("2026-07-30T09:15:00Z");
        SchedulingState before = card.schedulingState();
        SchedulingState after = scheduler.schedule(before, 5, TODAY);
        card.applySchedule(after, reviewedAt);

        em.flush();
        em.clear();

        Card loaded = em.find(Card.class, card.getId());
        assertEquals(2.6d, loaded.getEaseFactor(), EPSILON);
        assertEquals(1, loaded.getIntervalDays());
        assertEquals(1, loaded.getRepetitions());
        assertEquals(TODAY.plusDays(1), loaded.getDueDate());
        assertEquals(reviewedAt, loaded.getLastReviewedAt(), "timestamptz survives the round trip");
    }

    @Test
    @DisplayName("a review log records both sides of the transition")
    void reviewLogRoundTrips() {
        Topic topic = persistTopic("networks");
        Card card = new Card(USER, topic, "What is TCP slow start?", "Congestion control.", TODAY);
        card.applySchedule(new SchedulingState(2.5d, 10, 4, 0, TODAY), Instant.now());
        em.persist(card);

        SchedulingState before = card.schedulingState();
        SchedulingState after = scheduler.schedule(before, 2, TODAY);
        card.applySchedule(after, Instant.now());
        em.persist(ReviewLog.of(card, 2, before, after, Instant.now()));

        em.flush();
        em.clear();

        ReviewLog loaded = em.createQuery("select r from ReviewLog r", ReviewLog.class).getSingleResult();
        assertEquals(10, loaded.getIntervalBefore());
        assertEquals(1, loaded.getIntervalAfter());
        assertEquals(2.5d, loaded.getEaseFactorBefore(), EPSILON);
        assertEquals(2.5d, loaded.getEaseFactorAfter(), EPSILON, "a lapse leaves ease alone");
        assertEquals(0, loaded.getRepetitionsAfter());
        assertTrue(loaded.isLapse());
    }

    @Test
    @DisplayName("a topic slug is unique per user, not globally")
    void slugIsUniquePerUser() {
        persistTopic("operating-systems");
        em.persist(new Topic("someone-else", "Operating Systems", "operating-systems"));

        em.flush(); // Same slug, different user: allowed.

        // The persist is inside the assertion, not before it: identity ids force Hibernate
        // to run the insert immediately to obtain the generated key, so the violation
        // surfaces at persist() rather than being deferred to the flush.
        Exception thrown = assertThrows(Exception.class, () -> {
            em.persist(new Topic(USER, "Duplicate", "operating-systems"));
            em.flush();
        });
        assertTrue(rootMessage(thrown).contains("uq_topic_user_slug"),
                "expected the per-user slug constraint, got: " + thrown);
    }

    @Test
    @DisplayName("the database rejects an ease factor below the floor, not just Java")
    void databaseEnforcesEaseFactorFloor() {
        Topic topic = persistTopic("oop");
        Card card = new Card(USER, topic, "What is LSP?", "Liskov substitution.", TODAY);
        em.persist(card);
        em.flush();

        // Native SQL on purpose: this has to bypass SchedulingState's own validation to
        // show the constraint is doing real work.
        Exception thrown = assertThrows(Exception.class, () -> {
            em.createNativeQuery("update card set ease_factor = 1.0 where id = :id")
                    .setParameter("id", card.getId())
                    .executeUpdate();
            em.flush();
        });
        assertTrue(rootMessage(thrown).contains("ck_card_ease_factor"),
                "expected the ease factor check constraint, got: " + thrown);
    }
}
