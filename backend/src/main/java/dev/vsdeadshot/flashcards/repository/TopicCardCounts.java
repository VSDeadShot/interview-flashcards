package dev.vsdeadshot.flashcards.repository;

/**
 * One row of {@link TopicRepository#findTopicStats}: how many cards a topic holds and how many
 * of them are due.
 *
 * <p>An interface projection rather than a record built by a constructor expression, so the
 * query result stays a repository concern and the API's own shape does not have to be named
 * inside a query — {@code StatsService} maps this into what the contract publishes.
 */
public interface TopicCardCounts {

    Long getTopicId();

    String getName();

    long getTotal();

    long getDue();
}
