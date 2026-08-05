package dev.vsdeadshot.flashcards.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The endpoint over {@link dev.vsdeadshot.flashcards.service.StatsService}: that it answers,
 * in the shape the contract publishes, for the caller the key identifies. What the streak
 * <em>means</em> is {@code StatsServiceTest}'s, where a history can be built day by day
 * without pushing backdated rows through HTTP.
 *
 * <p>Not {@code @Transactional}, matching the other controller tests. Nothing in this
 * response is lazily loaded, so there is no proxy to catch out here — but a controller test
 * that rolled back while its neighbours did not would be the odd one out for no reason, and
 * the cleanup is two lines.
 */
@AutoConfigureMockMvc
@Import(FixedClockConfiguration.class)
class StatsControllerTest extends EmbeddedPostgresTest {

    private static final String PATH = "/api/v1/stats";
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

    private Card newCard(String front) {
        return cards.create(TEST_USER_ID, operatingSystems.getId(), front, "back");
    }

    @Test
    @DisplayName("returns every field the contract lists")
    void returnsTheContractsShape() throws Exception {
        Card reviewed = newCard("reviewed");
        newCard("waiting");
        study.review(TEST_USER_ID, reviewed.getId(), 5);

        mvc.perform(authorised(get(PATH)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.totalCards").value(2))
                // The reviewed card was pushed to tomorrow; only the untouched one is waiting.
                .andExpect(jsonPath("$.dueToday").value(1))
                .andExpect(jsonPath("$.reviewedToday").value(1))
                .andExpect(jsonPath("$.currentStreakDays").value(1))
                .andExpect(jsonPath("$.byTopic.length()").value(1))
                .andExpect(jsonPath("$.byTopic[0].topicId").value(operatingSystems.getId()))
                .andExpect(jsonPath("$.byTopic[0].name").value("Operating Systems"))
                .andExpect(jsonPath("$.byTopic[0].total").value(2))
                .andExpect(jsonPath("$.byTopic[0].due").value(1));
    }

    @Test
    @DisplayName("answers with zeros rather than 404 for a user who has nothing yet")
    void answersForAnEmptyAccount() throws Exception {
        topicRepository.deleteAll();

        mvc.perform(authorised(get(PATH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCards").value(0))
                .andExpect(jsonPath("$.dueToday").value(0))
                .andExpect(jsonPath("$.reviewedToday").value(0))
                // No cards means no history to walk, so the streak is zero rather than absent.
                .andExpect(jsonPath("$.currentStreakDays").value(0))
                .andExpect(jsonPath("$.byTopic").isArray())
                .andExpect(jsonPath("$.byTopic.length()").value(0));
    }

    @Test
    @DisplayName("leaves an archived card out of the totals")
    void excludesArchivedCards() throws Exception {
        newCard("kept");
        Card retired = newCard("retired");
        // Archived through the API rather than the service, so this is the same archiving a
        // client performs — the two must agree about what the totals then say.
        mvc.perform(authorised(delete("/api/v1/cards/" + retired.getId())));

        mvc.perform(authorised(get(PATH)))
                .andExpect(jsonPath("$.totalCards").value(1))
                .andExpect(jsonPath("$.byTopic[0].total").value(1));
    }

    @Test
    @DisplayName("counts only the caller's cards, topics and reviews")
    void isScopedToTheCaller() throws Exception {
        Topic theirs = topics.create(OTHER_USER, "Databases");
        Card theirCard = cards.create(OTHER_USER, theirs.getId(), "theirs", "theirs");
        study.review(OTHER_USER, theirCard.getId(), 5);

        mvc.perform(authorised(get(PATH)))
                .andExpect(jsonPath("$.totalCards").value(0))
                .andExpect(jsonPath("$.reviewedToday").value(0))
                .andExpect(jsonPath("$.currentStreakDays").value(0))
                .andExpect(jsonPath("$.byTopic.length()").value(1))
                .andExpect(jsonPath("$.byTopic[0].name").value("Operating Systems"));
    }

    @Test
    @DisplayName("does not leak the owner of the rows it counted")
    void doesNotPublishTheUserId() throws Exception {
        newCard("front");

        mvc.perform(authorised(get(PATH)))
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.byTopic[0].userId").doesNotExist());
    }

    @Test
    @DisplayName("requires the API key like every other route under /api")
    void requiresTheApiKey() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
    }
}
