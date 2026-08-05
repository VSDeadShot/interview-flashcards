package dev.vsdeadshot.flashcards.service;

import java.util.List;

/**
 * The answer to {@code GET /stats}, shaped by the contract rather than by the schema.
 *
 * <p>This is the one thing the API returns that has no DTO in {@code web/dto}, and the
 * controller hands it straight back. The rule those DTOs exist for is that an entity must
 * never reach the wire — because that would publish {@code userId} and make the JSON a
 * consequence of the JPA mapping. None of that applies here: this is a read model with no
 * identity, no lazy state and no mapping, assembled from counts. A mirror record in
 * {@code web/dto} would be a field-for-field copy whose only possible future is drifting
 * from this one.
 *
 * @param totalCards        cards in circulation; archived ones are not counted
 * @param dueToday          how many of those are due today or overdue
 * @param reviewedToday     reviews recorded since midnight, server-local
 * @param currentStreakDays consecutive days studied — see {@link StatsService} for what
 *                          "consecutive" means, which is the part with a decision in it
 */
public record Stats(
        long totalCards,
        long dueToday,
        long reviewedToday,
        int currentStreakDays,
        List<TopicStats> byTopic) {
}
