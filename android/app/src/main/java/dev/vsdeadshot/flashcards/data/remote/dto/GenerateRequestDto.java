package dev.vsdeadshot.flashcards.data.remote.dto;

/**
 * The body of {@code POST /cards/generate}.
 *
 * <p>A plain class rather than a record, like every DTO here: Moshi's record support needs
 * {@code java.lang.Record} reflection that Android's runtime does not provide, so a record DTO
 * compiles and then fails on a device.
 */
public class GenerateRequestDto {

    public long topicId;

    /** Optional narrowing within the topic. Null is a valid value and means the whole topic. */
    public String focus;

    /** Primitive, so a JSON null is refused rather than quietly read as zero. */
    public int count;
}
