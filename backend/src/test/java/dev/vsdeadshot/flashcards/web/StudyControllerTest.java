package dev.vsdeadshot.flashcards.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.vsdeadshot.flashcards.domain.Card;
import dev.vsdeadshot.flashcards.domain.Topic;
import dev.vsdeadshot.flashcards.repository.CardRepository;
import dev.vsdeadshot.flashcards.repository.ReviewLogRepository;
import dev.vsdeadshot.flashcards.repository.TopicRepository;
import dev.vsdeadshot.flashcards.service.CardService;
import dev.vsdeadshot.flashcards.service.StudyService;
import dev.vsdeadshot.flashcards.service.TopicService;
import dev.vsdeadshot.flashcards.support.EmbeddedPostgresTest;
import dev.vsdeadshot.flashcards.support.FixedClockConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The HTTP layer over {@link StudyService}: status codes, parameter binding, and the shape of
 * what comes back. What the scheduler does with a confidence, how the queue is ordered and what
 * the review log records are {@code StudyServiceTest}'s, and are not re-derived here.
 *
 * <p>Not {@code @Transactional}, for the reason spelled out in {@link CardControllerTest} — the
 * response is serialised after the service's transaction has closed, which is exactly where a
 * lazy association would fail in production and nowhere else.
 */
@AutoConfigureMockMvc
@Import(FixedClockConfiguration.class)
class StudyControllerTest extends EmbeddedPostgresTest {

    private static final String QUEUE = "/api/v1/study/queue";
    private static final String OTHER_USER = "someone-else";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TopicService topics;

    @Autowired
    private CardService cards;

    @Autowired
    private ReviewLogRepository reviewLogRepository;

    @Autowired
    private CardRepository cardRepository;

    @Autowired
    private TopicRepository topicRepository;

    private Topic operatingSystems;

    @BeforeEach
    void seedTopic() {
        operatingSystems = topics.create(TEST_USER_ID, "Operating Systems");
    }

    /** In dependency order: logs reference cards, cards reference topics. */
    @AfterEach
    void clean() {
        reviewLogRepository.deleteAll();
        cardRepository.deleteAll();
        topicRepository.deleteAll();
    }

    private static String reviewPath(long cardId) {
        return "/api/v1/study/" + cardId + "/review";
    }

    private static MockHttpServletRequestBuilder authorised(MockHttpServletRequestBuilder request) {
        return request.header(ApiKeyFilter.HEADER, TEST_API_KEY);
    }

    private static MockHttpServletRequestBuilder review(long cardId, String body) {
        return authorised(post(reviewPath(cardId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static String confidence(int value) {
        return "{\"confidence\": %d}".formatted(value);
    }

    private Card newCard(String front) {
        return cards.create(TEST_USER_ID, operatingSystems.getId(), front, "back");
    }

    @Nested
    @DisplayName("GET /study/queue")
    class Queue {

        @Test
        @DisplayName("returns the due cards in the same shape /cards does")
        void returnsDueCards() throws Exception {
            newCard("What is a deadlock?");

            mvc.perform(authorised(get(QUEUE)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].front").value("What is a deadlock?"))
                    // The same DTO as the listing, read off a lazy association with no session
                    // open. A queue-specific shape is what this endpoint is deliberately not.
                    .andExpect(jsonPath("$[0].topicId").value(operatingSystems.getId()))
                    .andExpect(jsonPath("$[0].dueDate")
                            .value(FixedClockConfiguration.TODAY.toString()));
        }

        @Test
        @DisplayName("leaves out a card that has been reviewed into the future")
        void excludesCardsNotYetDue() throws Exception {
            Card card = newCard("front");
            mvc.perform(review(card.getId(), confidence(5)));

            mvc.perform(authorised(get(QUEUE))).andExpect(jsonPath("$.length()").value(0));
        }

        @Test
        @DisplayName("leaves out an archived card")
        void excludesArchivedCards() throws Exception {
            Card card = newCard("front");
            cards.archive(TEST_USER_ID, card.getId());

            mvc.perform(authorised(get(QUEUE))).andExpect(jsonPath("$.length()").value(0));
        }

        @Test
        @DisplayName("never offers another user's card")
        void isScopedToTheCaller() throws Exception {
            Topic theirs = topics.create(OTHER_USER, "Databases");
            cards.create(OTHER_USER, theirs.getId(), "theirs", "theirs");

            mvc.perform(authorised(get(QUEUE))).andExpect(jsonPath("$.length()").value(0));
        }

        @Test
        @DisplayName("returns no more than the requested limit")
        void honoursTheLimit() throws Exception {
            newCard("one");
            newCard("two");

            mvc.perform(authorised(get(QUEUE)).param("limit", "1"))
                    .andExpect(jsonPath("$.length()").value(1));
        }

        /**
         * The one assertion that pins the controller's {@code defaultValue}, which is why it
         * pays for the extra card: with any other default in place, this returns the wrong
         * count. Seeded one past the limit so it cannot pass by simply running out of cards.
         */
        @Test
        @DisplayName("returns the service's default number of cards when no limit is given")
        void defaultsToTheServiceLimit() throws Exception {
            for (int i = 0; i <= StudyService.DEFAULT_LIMIT; i++) {
                newCard("card " + i);
            }

            mvc.perform(authorised(get(QUEUE)))
                    .andExpect(jsonPath("$.length()").value(StudyService.DEFAULT_LIMIT));
        }

        @Test
        @DisplayName("rejects a limit of zero as 400 rather than answering with nothing")
        void rejectsANonPositiveLimit() throws Exception {
            mvc.perform(authorised(get(QUEUE)).param("limit", "0"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.detail").value("limit must be at least 1, was 0"));
        }

        /**
         * Handled by Spring rather than by anything in this project, and asserted because it
         * has to arrive in the same format: a client parsing errors should not need to know
         * which layer produced one.
         */
        @Test
        @DisplayName("rejects a limit that is not a number as 400 in the same format")
        void rejectsANonNumericLimit() throws Exception {
            mvc.perform(authorised(get(QUEUE)).param("limit", "twenty"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        }
    }

    @Nested
    @DisplayName("POST /study/{cardId}/review")
    class Review {

        @Test
        @DisplayName("answers with the rescheduled card")
        void reschedulesTheCard() throws Exception {
            Card card = newCard("front");

            mvc.perform(review(card.getId(), confidence(5)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(card.getId()))
                    .andExpect(jsonPath("$.repetitions").value(1))
                    .andExpect(jsonPath("$.intervalDays").value(1))
                    .andExpect(jsonPath("$.easeFactor").value(2.6))
                    .andExpect(jsonPath("$.dueDate")
                            .value(FixedClockConfiguration.TODAY.plusDays(1).toString()))
                    .andExpect(jsonPath("$.lastReviewedAt").isString());
        }

        /**
         * The offline client's request: studied yesterday, sent today. The schedule has to run
         * from the day it happened, or a queued review silently becomes a review of the day it
         * was uploaded.
         */
        @Test
        @DisplayName("accepts the time the review happened and schedules from it")
        void acceptsAReviewedAtFromTheClient() throws Exception {
            Card card = newCard("front");
            Instant yesterday = FixedClockConfiguration.NOW.minus(Duration.ofDays(1));

            mvc.perform(review(card.getId(), """
                            {"confidence": 5, "reviewedAt": "%s"}""".formatted(yesterday)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.intervalDays").value(1))
                    // One day on from yesterday is today: back in the queue, correctly.
                    .andExpect(jsonPath("$.dueDate")
                            .value(FixedClockConfiguration.TODAY.toString()))
                    .andExpect(jsonPath("$.lastReviewedAt").value(yesterday.toString()));
        }

        @Test
        @DisplayName("rejects a review dated in the future as 400")
        void rejectsAFutureReviewedAt() throws Exception {
            Card card = newCard("front");
            Instant tomorrow = FixedClockConfiguration.NOW.plus(Duration.ofDays(1));

            mvc.perform(review(card.getId(), """
                            {"confidence": 5, "reviewedAt": "%s"}""".formatted(tomorrow)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail")
                            .value("reviewedAt must not be in the future, was " + tomorrow));
        }

        @Test
        @DisplayName("rejects a confidence outside 1 to 5 as 400, naming the value")
        void rejectsAnOutOfRangeConfidence() throws Exception {
            Card card = newCard("front");

            mvc.perform(review(card.getId(), confidence(7)))
                    .andExpect(status().isBadRequest())
                    // The service's message, reaching the caller intact. The range is checked
                    // there and not on the DTO precisely so this sentence is what comes back.
                    .andExpect(jsonPath("$.detail").value("confidence must be between 1 and 5, was 7"));
        }

        @Test
        @DisplayName("rejects a body with no confidence rather than reading it as a lapse")
        void rejectsAMissingConfidence() throws Exception {
            Card card = newCard("front");

            mvc.perform(review(card.getId(), "{}")).andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("reports an unknown card as 404")
        void reportsAnUnknownCard() throws Exception {
            mvc.perform(review(999999, confidence(4))).andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("reports another user's card as 404, not 403")
        void refusesSomebodyElsesCard() throws Exception {
            Topic theirs = topics.create(OTHER_USER, "Databases");
            Card theirCard = cards.create(OTHER_USER, theirs.getId(), "theirs", "theirs");

            mvc.perform(review(theirCard.getId(), confidence(4)))
                    .andExpect(status().isNotFound());
        }

        /**
         * The HTTP half of what {@code DELETE /cards/{id}} means. A client holding a queue from
         * before the delete must not be able to quietly reschedule a card the user has retired,
         * and an archived card reads as absent rather than forbidden — the same answer an
         * unknown id gets, so nothing about it is discoverable.
         */
        @Test
        @DisplayName("reports an archived card as 404")
        void refusesAnArchivedCard() throws Exception {
            Card card = newCard("front");
            cards.archive(TEST_USER_ID, card.getId());

            mvc.perform(review(card.getId(), confidence(5))).andExpect(status().isNotFound());
        }
    }

    /**
     * The retry an outbox performs when it never learned whether the first attempt landed.
     * Applying SM-2 twice would push the card an extra interval with nothing reporting it, so
     * these are the assertions that make offline replay safe.
     */
    @Nested
    @DisplayName("POST /study/{cardId}/review with a client key")
    class IdempotentReview {

        @Test
        @DisplayName("answers 200 both times and schedules the card once")
        void appliesOnceOverHttp() throws Exception {
            Card card = newCard("front");
            String body = """
                    {"confidence": 5, "clientReviewId": "%s"}""".formatted(UUID.randomUUID());

            mvc.perform(review(card.getId(), body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.repetitions").value(1));

            mvc.perform(review(card.getId(), body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.repetitions").value(1))
                    .andExpect(jsonPath("$.intervalDays").value(1))
                    .andExpect(jsonPath("$.dueDate")
                            .value(FixedClockConfiguration.TODAY.plusDays(1).toString()));
        }

        @Test
        @DisplayName("answers 409 and says not to retry when a key is reused for something else")
        void refusesAReusedKey() throws Exception {
            Card card = newCard("front");
            UUID key = UUID.randomUUID();
            mvc.perform(review(card.getId(), """
                    {"confidence": 5, "clientReviewId": "%s"}""".formatted(key)));

            mvc.perform(review(card.getId(), """
                            {"confidence": 2, "clientReviewId": "%s"}""".formatted(key)))
                    .andExpect(status().isConflict())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    // The field, not the sentence: an outbox has to tell this apart from the
                    // other 409 on this endpoint, which it is meant to retry.
                    .andExpect(jsonPath("$.retryable").value(false));
        }

        @Test
        @DisplayName("applies both when two reviews carry different keys")
        void distinctKeysBothApply() throws Exception {
            Card card = newCard("front");

            mvc.perform(review(card.getId(), """
                    {"confidence": 5, "clientReviewId": "%s"}""".formatted(UUID.randomUUID())));
            mvc.perform(review(card.getId(), """
                            {"confidence": 5, "clientReviewId": "%s"}""".formatted(UUID.randomUUID())))
                    .andExpect(status().isOk())
                    // Studying the same card twice in a day is allowed; only a repeated key
                    // means the same review.
                    .andExpect(jsonPath("$.repetitions").value(2));
        }
    }

    @Test
    @DisplayName("requires the API key like every other route under /api")
    void requiresTheApiKey() throws Exception {
        mvc.perform(get(QUEUE)).andExpect(status().isUnauthorized());
    }
}
