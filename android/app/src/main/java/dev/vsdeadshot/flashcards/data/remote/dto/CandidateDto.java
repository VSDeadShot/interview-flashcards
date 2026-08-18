package dev.vsdeadshot.flashcards.data.remote.dto;

/**
 * One generated candidate.
 *
 * <p>Deliberately not {@link CardDto}: a candidate has no id and no schedule, and reusing the card
 * shape would mean carrying fields that are meaningless until somebody accepts it.
 */
public class CandidateDto {

    public String front;

    public String back;
}
