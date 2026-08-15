package dev.vsdeadshot.flashcards.data.local;

/**
 * One topic's line on a stats screen, counted from the cache.
 *
 * <p>A plain class rather than a record because Room constructs it from a cursor, and the plain
 * class is the shape every other row-mapped type here already uses.
 */
public class TopicStatsRow {

    public long topicId;

    public String name;

    public int total;

    public int due;
}
