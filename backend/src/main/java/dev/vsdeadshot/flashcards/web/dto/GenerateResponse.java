package dev.vsdeadshot.flashcards.web.dto;

import java.util.List;

/**
 * A wrapper rather than a bare array, which breaks from {@code [Topic]} and {@code [Card]}.
 *
 * <p>Those return collections of stored resources; this returns a computed batch. The wrapper
 * leaves room for the one thing likely to be wanted later — a note that the output was truncated
 * or filtered — without that being a breaking change to a shape clients already parse.
 */
public record GenerateResponse(List<CandidateResponse> candidates) {
}
