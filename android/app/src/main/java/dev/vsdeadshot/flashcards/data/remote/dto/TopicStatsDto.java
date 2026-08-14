package dev.vsdeadshot.flashcards.data.remote.dto;

/** One topic's line in {@link StatsDto#byTopic}. */
public class TopicStatsDto {

    public long topicId;
    public String name;
    public long total;
    public long due;
}
