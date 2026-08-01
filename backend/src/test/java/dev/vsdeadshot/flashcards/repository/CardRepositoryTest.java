package dev.vsdeadshot.flashcards.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vsdeadshot.flashcards.domain.Card;
import dev.vsdeadshot.flashcards.domain.Topic;
import dev.vsdeadshot.flashcards.support.EmbeddedPostgresTest;
import jakarta.persistence.EntityManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class CardRepositoryTest extends EmbeddedPostgresTest {

    private static final String USER = "vedansh";
    private static final String OTHER_USER = "someone-else";
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 1);

    /**
     * The queue query written by hand, used only by {@link Index#studyQueueUsesThePartialIndex}
     * to inspect a plan. {@code queueMatchesTheHandWrittenQuery} pins it against the JPQL so a
     * change to one that is not mirrored in the other fails rather than silently drifting.
     */
    private static final String QUEUE_SQL = """
            select id from card
            where user_id = ? and archived = false and due_date <= ?
            order by due_date asc, id asc
            """;

    @Autowired
    private EntityManager em;

    @Autowired
    private CardRepository cards;

    private Topic operatingSystems;
    private Topic dbms;

    private Card overdueByThree;
    private Card overdueByOne;
    private Card dueToday;
    private Card archived;
    private Card notYetDue;

    @BeforeEach
    void seed() {
        operatingSystems = topic(USER, "operating-systems");
        dbms = topic(USER, "dbms");
        Topic theirs = topic(OTHER_USER, "their-topic");

        overdueByThree = card(USER, operatingSystems, TODAY.minusDays(3), false);
        overdueByOne = card(USER, dbms, TODAY.minusDays(1), false);
        dueToday = card(USER, operatingSystems, TODAY, false);
        archived = card(USER, operatingSystems, TODAY.minusDays(5), true);
        notYetDue = card(USER, operatingSystems, TODAY.plusDays(2), false);
        card(OTHER_USER, theirs, TODAY.minusDays(10), false);

        em.flush();
    }

    private Topic topic(String userId, String slug) {
        Topic topic = new Topic(userId, slug, slug);
        em.persist(topic);
        return topic;
    }

    private Card card(String userId, Topic topic, LocalDate dueDate, boolean isArchived) {
        // The constructor makes a card due on the date it is created, so passing the wanted
        // due date as "today" is enough — no scheduler round trip needed to place a fixture.
        Card card = new Card(userId, topic, "front " + dueDate, "back", dueDate);
        if (isArchived) {
            card.archive();
        }
        em.persist(card);
        return card;
    }

    private static List<Long> idsOf(List<Card> found) {
        return found.stream().map(Card::getId).toList();
    }

    @Nested
    @DisplayName("the study queue")
    class Queue {

        @Test
        @DisplayName("returns the user's due, unarchived cards oldest first")
        void returnsDueCardsOldestFirst() {
            List<Card> queue = cards.findStudyQueue(USER, TODAY, Limit.unlimited());

            assertEquals(
                    List.of(overdueByThree.getId(), overdueByOne.getId(), dueToday.getId()),
                    idsOf(queue),
                    "the longest overdue card is studied first, and a card due today is included");
        }

        @Test
        @DisplayName("excludes archived cards even when they are the most overdue")
        void excludesArchivedCards() {
            List<Card> queue = cards.findStudyQueue(USER, TODAY, Limit.unlimited());

            assertFalse(idsOf(queue).contains(archived.getId()),
                    "archiving takes a card out of circulation without deleting its history");
        }

        @Test
        @DisplayName("excludes cards that are not due yet")
        void excludesFutureCards() {
            List<Card> queue = cards.findStudyQueue(USER, TODAY, Limit.unlimited());

            assertFalse(idsOf(queue).contains(notYetDue.getId()));
        }

        @Test
        @DisplayName("never returns another user's cards")
        void isScopedToOneUser() {
            List<Card> queue = cards.findStudyQueue(USER, TODAY, Limit.unlimited());

            assertTrue(queue.stream().allMatch(card -> USER.equals(card.getUserId())),
                    "the ownership filter is in the query, not left to the caller");
        }

        @Test
        @DisplayName("takes the oldest due cards when a limit is applied, not an arbitrary subset")
        void honoursTheLimit() {
            List<Card> queue = cards.findStudyQueue(USER, TODAY, Limit.of(2));

            assertEquals(List.of(overdueByThree.getId(), overdueByOne.getId()), idsOf(queue),
                    "the limit is applied after the ordering, so the most overdue cards survive it");
        }
    }

    @Nested
    @DisplayName("listing cards")
    class Listing {

        @Test
        @DisplayName("hides archived cards unless they are asked for")
        void hidesArchivedByDefault() {
            List<Long> visible = idsOf(cards.findForListing(USER, null, false));

            assertFalse(visible.contains(archived.getId()));
            assertTrue(visible.contains(notYetDue.getId()), "not due is not the same as not listed");
        }

        @Test
        @DisplayName("includes archived cards when asked")
        void includesArchivedOnRequest() {
            List<Long> visible = idsOf(cards.findForListing(USER, null, true));

            assertTrue(visible.contains(archived.getId()));
            assertEquals(5, visible.size(), "every card of this user, archived or not");
        }

        @Test
        @DisplayName("filters by topic when one is given")
        void filtersByTopic() {
            List<Long> visible = idsOf(cards.findForListing(USER, dbms.getId(), false));

            assertEquals(List.of(overdueByOne.getId()), visible);
        }

        @Test
        @DisplayName("treats a null topic as every topic")
        void nullTopicMeansAllTopics() {
            List<Long> visible = idsOf(cards.findForListing(USER, null, false));

            assertEquals(4, visible.size(), "both topics, archived excluded");
        }
    }

    @Nested
    @DisplayName("looking a card up by id")
    class ById {

        @Test
        @DisplayName("finds the card when the user owns it")
        void findsOwnCard() {
            assertTrue(cards.findByIdAndUserId(dueToday.getId(), USER).isPresent());
        }

        @Test
        @DisplayName("refuses a card belonging to another user rather than returning it")
        void refusesAnotherUsersCard() {
            assertTrue(cards.findByIdAndUserId(dueToday.getId(), OTHER_USER).isEmpty(),
                    "an existing id owned by someone else must read as not found, not as forbidden");
        }
    }

    @Nested
    @DisplayName("the partial index")
    class Index {

        /**
         * The reason {@code idx_card_due} is partial is that the queue never wants archived
         * rows, and that only pays off if the planner can prove the query's predicate implies
         * the index's. Postgres is reasonably good at that proof — {@code archived <> true}
         * satisfies it just as well as {@code archived = false} — but it cannot prove anything
         * from a predicate that is not there, so dropping the {@code archived} filter silently
         * costs a sequential scan on the hottest query in the app. Asserting on the plan is
         * what catches that; the query still returns the right rows either way.
         */
        @Test
        @DisplayName("can serve the study queue query")
        void studyQueueUsesThePartialIndex() {
            StringBuilder plan = new StringBuilder();

            em.unwrap(Session.class).doWork(connection -> {
                // Rolled back with the test. Without it the planner sequentially scans a table
                // this small no matter what indexes exist, and the assertion would prove nothing.
                try (Statement statement = connection.createStatement()) {
                    statement.execute("set local enable_seqscan = off");
                }
                try (PreparedStatement explain = connection.prepareStatement("explain " + QUEUE_SQL)) {
                    explain.setString(1, USER);
                    explain.setObject(2, TODAY);
                    try (ResultSet rows = explain.executeQuery()) {
                        while (rows.next()) {
                            plan.append(rows.getString(1)).append('\n');
                        }
                    }
                }
            });

            assertTrue(plan.toString().contains("idx_card_due"),
                    "the queue must be served by the partial index, plan was:\n" + plan);
        }

        @Test
        @DisplayName("is indexing the same rows the repository query selects")
        void queueMatchesTheHandWrittenQuery() {
            List<Long> fromRepository = idsOf(cards.findStudyQueue(USER, TODAY, Limit.unlimited()));
            List<Long> fromSql = new ArrayList<>();

            em.unwrap(Session.class).doWork(connection -> {
                try (PreparedStatement query = connection.prepareStatement(QUEUE_SQL)) {
                    query.setString(1, USER);
                    query.setObject(2, TODAY);
                    try (ResultSet rows = query.executeQuery()) {
                        while (rows.next()) {
                            fromSql.add(rows.getLong(1));
                        }
                    }
                }
            });

            assertEquals(fromSql, fromRepository,
                    "the SQL the index test explains must stay equivalent to the JPQL in use");
        }
    }
}
