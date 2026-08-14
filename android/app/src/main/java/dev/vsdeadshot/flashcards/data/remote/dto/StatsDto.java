package dev.vsdeadshot.flashcards.data.remote.dto;

import java.util.List;

/**
 * The answer to {@code GET /stats}.
 *
 * <p>{@code currentStreakDays} is the server's figure and the only one the app will ever show.
 * The client cannot compute it: the rule skips days on which nothing was due, which needs the
 * whole review history and every card's due date as it stood on each of those days. A local
 * approximation would be wrong in exactly the situation the forgiving rule exists for.
 */
public class StatsDto {

    public long totalCards;
    public long dueToday;
    public long reviewedToday;
    public int currentStreakDays;
    public List<TopicStatsDto> byTopic;
}
