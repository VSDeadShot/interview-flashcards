package dev.vsdeadshot.flashcards.data.remote.dto;

import java.util.List;

/** A wrapper, matching the contract: the batch is computed, not a collection of stored rows. */
public class GenerateResponseDto {

    public List<CandidateDto> candidates;
}
