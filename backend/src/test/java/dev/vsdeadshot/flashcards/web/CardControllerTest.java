package dev.vsdeadshot.flashcards.web;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Deliberately <strong>not</strong> {@code @Transactional}, unlike every other database test
 * here.
 *
 * <p>A test transaction keeps one Hibernate session open across the whole method, so the
 * request would serialise its cards inside the session that loaded them. Production does not
 * work that way: {@code open-in-view} is off, so the session closes when the service returns
 * and {@code CardResponse.from} reads {@code card.getTopic()} — a lazy association — with
 * nothing open behind it. Rolling back would have hidden whether that works. The price is
 * cleaning up by hand in {@link #clean()}.
 */
@AutoConfigureMockMvc
@Import(FixedClockConfiguration.class)
class CardControllerTest extends EmbeddedPostgresTest {

    private static final String PATH = "/api/v1/cards";
    private static final String OTHER_USER = "someone-else";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TopicService topics;

    @Autowired
    private CardService cards;

    @Autowired
    private StudyService study;

    @Autowired
    private CardRepository cardRepository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private ReviewLogRepository reviewLogRepository;

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

    private static MockHttpServletRequestBuilder authorised(MockHttpServletRequestBuilder request) {
        return request.header(ApiKeyFilter.HEADER, TEST_API_KEY);
    }

    private static MockHttpServletRequestBuilder json(
            MockHttpServletRequestBuilder request, String body) {
        return authorised(request).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String cardBody(long topicId) {
        return """
                {"topicId": %d, "front": "What is a deadlock?", "back": "Four Coffman conditions"}"""
                .formatted(topicId);
    }

    @Nested
    @DisplayName("GET /cards")
    class List {

        @Test
        @DisplayName("returns the caller's cards")
        void listsCards() throws Exception {
            cards.create(TEST_USER_ID, operatingSystems.getId(), "front", "back");

            mvc.perform(authorised(get(PATH)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].front").value("front"));
        }

        @Test
        @DisplayName("does not return another user's cards")
        void isScopedToTheCaller() throws Exception {
            Topic theirs = topics.create(OTHER_USER, "Databases");
            cards.create(OTHER_USER, theirs.getId(), "theirs", "theirs");

            mvc.perform(authorised(get(PATH))).andExpect(jsonPath("$.length()").value(0));
        }

        @Test
        @DisplayName("filters to one topic when asked")
        void filtersByTopic() throws Exception {
            Topic databases = topics.create(TEST_USER_ID, "Databases");
            cards.create(TEST_USER_ID, operatingSystems.getId(), "os", "os");
            cards.create(TEST_USER_ID, databases.getId(), "db", "db");

            mvc.perform(authorised(get(PATH)).param("topicId", databases.getId().toString()))
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].front").value("db"));
        }

        /**
         * Reads the topic id off a lazy association after the transaction has closed. If the
         * proxy could not answer that from the foreign key it already holds, this is where the
         * whole listing would fail — which is the reason this class does not roll back.
         */
        @Test
        @DisplayName("returns the DTO's fields, including the topic id and the schedule")
        void returnsTheDtoShape() throws Exception {
            cards.create(TEST_USER_ID, operatingSystems.getId(), "front", "back");

            mvc.perform(authorised(get(PATH)))
                    .andExpect(jsonPath("$[0].id").isNumber())
                    .andExpect(jsonPath("$[0].topicId").value(operatingSystems.getId()))
                    .andExpect(jsonPath("$[0].easeFactor").value(2.5))
                    .andExpect(jsonPath("$[0].intervalDays").value(0))
                    .andExpect(jsonPath("$[0].repetitions").value(0))
                    .andExpect(jsonPath("$[0].lapses").value(0))
                    .andExpect(jsonPath("$[0].dueDate").isString())
                    .andExpect(jsonPath("$[0].archived").value(false))
                    // Never reviewed, and the field is present rather than dropped so the client
                    // can tell "never" from "the server did not say".
                    .andExpect(jsonPath("$[0].lastReviewedAt").doesNotExist())
                    // Null for a card the server made itself. Only a card a client created
                    // under its own key carries one.
                    .andExpect(jsonPath("$[0].clientCardId").doesNotExist())
                    .andExpect(jsonPath("$[0].userId").doesNotExist());
        }
    }

    @Nested
    @DisplayName("POST /cards")
    class Create {

        @Test
        @DisplayName("answers 201 with a card due today and never reviewed")
        void createsACard() throws Exception {
            mvc.perform(json(post(PATH), cardBody(operatingSystems.getId())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").isNumber())
                    .andExpect(jsonPath("$.topicId").value(operatingSystems.getId()))
                    .andExpect(jsonPath("$.front").value("What is a deadlock?"))
                    .andExpect(jsonPath("$.repetitions").value(0))
                    // "Today" is the injected clock's, not the wall clock's. Imported rather
                    // than relied on: the fixed clock is a @Configuration under the scanned
                    // package, so every context already gets it — which is easy to depend on
                    // by accident and would leave this asserting nothing the day that changes.
                    .andExpect(jsonPath("$.dueDate")
                            .value(FixedClockConfiguration.TODAY.toString()));
        }

        @Test
        @DisplayName("reports another user's topic as 404, not 403")
        void refusesSomebodyElsesTopic() throws Exception {
            Topic theirs = topics.create(OTHER_USER, "Databases");

            mvc.perform(json(post(PATH), cardBody(theirs.getId())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("rejects a blank front as 400")
        void rejectsABlankFront() throws Exception {
            mvc.perform(json(post(PATH), """
                            {"topicId": %d, "front": "   ", "back": "back"}"""
                            .formatted(operatingSystems.getId())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        @DisplayName("rejects a body with no topic as 400 rather than guessing one")
        void rejectsAMissingTopicId() throws Exception {
            mvc.perform(json(post(PATH), """
                            {"front": "front", "back": "back"}"""))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("PUT /cards/{id}")
    class Update {

        @Test
        @DisplayName("replaces the text and the topic but leaves the schedule alone")
        void updatesWithoutResettingProgress() throws Exception {
            Card card = cards.create(TEST_USER_ID, operatingSystems.getId(), "front", "back");
            // Give it progress worth losing: a confident review moves it off the defaults.
            study.review(TEST_USER_ID, card.getId(), 5);
            Topic databases = topics.create(TEST_USER_ID, "Databases");

            mvc.perform(json(put(PATH + "/" + card.getId()), """
                            {"topicId": %d, "front": "corrected", "back": "also corrected"}"""
                            .formatted(databases.getId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.front").value("corrected"))
                    .andExpect(jsonPath("$.topicId").value(databases.getId()))
                    .andExpect(jsonPath("$.repetitions").value(1))
                    .andExpect(jsonPath("$.intervalDays").value(1))
                    .andExpect(jsonPath("$.lastReviewedAt").isString());
        }

        @Test
        @DisplayName("reports an unknown card as 404")
        void reportsAnUnknownCard() throws Exception {
            mvc.perform(json(put(PATH + "/999999"), cardBody(operatingSystems.getId())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("refuses to move a card into another user's topic")
        void refusesSomebodyElsesTopic() throws Exception {
            Card card = cards.create(TEST_USER_ID, operatingSystems.getId(), "front", "back");
            Topic theirs = topics.create(OTHER_USER, "Databases");

            mvc.perform(json(put(PATH + "/" + card.getId()), cardBody(theirs.getId())))
                    .andExpect(status().isNotFound());
        }
    }

    /**
     * The other half of what archiving means — an archived card cannot be reviewed either, which
     * is why it is one flag and not two ideas — is asserted over HTTP in
     * {@code StudyControllerTest}, where that route lives.
     */
    @Nested
    @DisplayName("DELETE /cards/{id}")
    class Delete {

        @Test
        @DisplayName("archives the card instead of removing the row")
        void archivesRatherThanDeletes() throws Exception {
            Card card = cards.create(TEST_USER_ID, operatingSystems.getId(), "front", "back");

            mvc.perform(authorised(delete(PATH + "/" + card.getId())))
                    .andExpect(status().isNoContent());

            assertTrue(cardRepository.findById(card.getId()).orElseThrow().isArchived(),
                    "the row survives so the review history that references it still resolves");
        }

        @Test
        @DisplayName("takes the card out of the default listing but not out of the database")
        void disappearsFromTheDefaultListing() throws Exception {
            Card card = cards.create(TEST_USER_ID, operatingSystems.getId(), "front", "back");
            mvc.perform(authorised(delete(PATH + "/" + card.getId())));

            mvc.perform(authorised(get(PATH)))
                    .andExpect(jsonPath("$.length()").value(0));
            mvc.perform(authorised(get(PATH)).param("includeArchived", "true"))
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].archived").value(true));
        }

        @Test
        @DisplayName("succeeds again when the card is already archived")
        void isIdempotent() throws Exception {
            Card card = cards.create(TEST_USER_ID, operatingSystems.getId(), "front", "back");

            mvc.perform(authorised(delete(PATH + "/" + card.getId())))
                    .andExpect(status().isNoContent());
            mvc.perform(authorised(delete(PATH + "/" + card.getId())))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("reports another user's card as 404")
        void refusesSomebodyElsesCard() throws Exception {
            Topic theirs = topics.create(OTHER_USER, "Databases");
            Card theirCard = cards.create(OTHER_USER, theirs.getId(), "theirs", "theirs");

            mvc.perform(authorised(delete(PATH + "/" + theirCard.getId())))
                    .andExpect(status().isNotFound());
        }
    }

    /**
     * The offline client's retry. What the status code says matters here: the body is the same
     * card both times, so a client needs no branch, but 201 would be claiming a creation that
     * did not happen on the second request.
     */
    @Nested
    @DisplayName("POST /cards with a client key")
    class IdempotentCreate {

        @Test
        @DisplayName("answers 201 then 200 for the same card")
        void answers201Then200() throws Exception {
            String body = """
                    {"topicId": %d, "front": "front", "back": "back", "clientCardId": "%s"}"""
                    .formatted(operatingSystems.getId(), UUID.randomUUID());

            String created = mvc.perform(json(post(PATH), body))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();

            mvc.perform(json(post(PATH), body))
                    .andExpect(status().isOk())
                    .andExpect(content().json(created, JsonCompareMode.STRICT));

            mvc.perform(authorised(get(PATH)))
                    .andExpect(jsonPath("$.length()").value(1));
        }

        @Test
        @DisplayName("still creates a second card under a second key")
        void distinctKeysCreateDistinctCards() throws Exception {
            mvc.perform(json(post(PATH), cardBodyWithKey(UUID.randomUUID())))
                    .andExpect(status().isCreated());
            mvc.perform(json(post(PATH), cardBodyWithKey(UUID.randomUUID())))
                    .andExpect(status().isCreated());

            mvc.perform(authorised(get(PATH))).andExpect(jsonPath("$.length()").value(2));
        }

        /**
         * The reason the key is on the response at all. A create whose response was lost leaves
         * the card queued locally and returns it from the next listing under a server id the
         * client has never seen — so without the key on the listing, the client cannot tell its
         * own card from a new one, and the user sees it twice.
         */
        @Test
        @DisplayName("echoes the key on the card, in the listing as well as on the create")
        void echoesTheKey() throws Exception {
            UUID key = UUID.randomUUID();

            mvc.perform(json(post(PATH), cardBodyWithKey(key)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.clientCardId").value(key.toString()));

            mvc.perform(authorised(get(PATH)))
                    .andExpect(jsonPath("$[0].clientCardId").value(key.toString()));
        }

        @Test
        @DisplayName("echoes the same key on the replay")
        void echoesTheKeyOnTheReplay() throws Exception {
            UUID key = UUID.randomUUID();
            mvc.perform(json(post(PATH), cardBodyWithKey(key)))
                    .andExpect(status().isCreated());

            mvc.perform(json(post(PATH), cardBodyWithKey(key)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.clientCardId").value(key.toString()));
        }

        @Test
        @DisplayName("rejects a key that is not a UUID as 400")
        void rejectsAMalformedKey() throws Exception {
            mvc.perform(json(post(PATH), """
                            {"topicId": %d, "front": "f", "back": "b", "clientCardId": "not-a-uuid"}"""
                            .formatted(operatingSystems.getId())))
                    .andExpect(status().isBadRequest());
        }

        private String cardBodyWithKey(UUID key) {
            return """
                    {"topicId": %d, "front": "front", "back": "back", "clientCardId": "%s"}"""
                    .formatted(operatingSystems.getId(), key);
        }
    }

    @Test
    @DisplayName("requires the API key like every other route under /api")
    void requiresTheApiKey() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
    }
}
