package dev.vsdeadshot.flashcards.service;

/**
 * One topic's line in {@link Stats#byTopic()}.
 *
 * <p>The name is repeated here rather than left for the client to look up by id: the stats
 * screen would otherwise have to hold the topic list to render a single row, and the whole
 * point of this endpoint is that it answers in one request.
 *
 * @param total cards in circulation under this topic, archived ones excluded
 * @param due   how many of those are due today or overdue
 */
public record TopicStats(Long topicId, String name, long total, long due) {
}
