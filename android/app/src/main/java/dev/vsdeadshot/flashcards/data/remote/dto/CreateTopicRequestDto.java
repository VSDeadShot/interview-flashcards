package dev.vsdeadshot.flashcards.data.remote.dto;

/** The body of {@code POST /topics}. The slug is the server's to derive. */
public class CreateTopicRequestDto {

    public final String name;

    public CreateTopicRequestDto(String name) {
        this.name = name;
    }
}
