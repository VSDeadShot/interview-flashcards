package dev.vsdeadshot.flashcards.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.vsdeadshot.flashcards.service.TopicService;
import dev.vsdeadshot.flashcards.support.EmbeddedPostgresTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Goes through the real HTTP stack — filter, dispatcher, Jackson, advice — rather than calling
 * the controller as an object, because everything worth checking here happens on the way in and
 * out: the owner comes from the key, validation runs before the service, and the response is
 * the DTO's shape and not the entity's.
 *
 * <p>Seeded data belongs to {@code TEST_USER_ID}, since that is who the filter says the caller
 * is. {@code @Transactional} rolls it back.
 */
@AutoConfigureMockMvc
@Transactional
class TopicControllerTest extends EmbeddedPostgresTest {

    private static final String PATH = "/api/v1/topics";
    private static final String OTHER_USER = "someone-else";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TopicService topics;

    private static MockHttpServletRequestBuilder authorised(MockHttpServletRequestBuilder request) {
        return request.header(ApiKeyFilter.HEADER, TEST_API_KEY);
    }

    @Nested
    @DisplayName("GET /topics")
    class List {

        @Test
        @DisplayName("returns the caller's topics, alphabetically")
        void listsTopics() throws Exception {
            topics.create(TEST_USER_ID, "Operating Systems");
            topics.create(TEST_USER_ID, "Databases");

            mvc.perform(authorised(get(PATH)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].name").value("Databases"))
                    .andExpect(jsonPath("$[1].name").value("Operating Systems"));
        }

        @Test
        @DisplayName("does not return another user's topics")
        void isScopedToTheCaller() throws Exception {
            topics.create(OTHER_USER, "Networks");

            mvc.perform(authorised(get(PATH)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }

        @Test
        @DisplayName("returns the DTO's fields and not the entity's")
        void returnsTheDtoShape() throws Exception {
            topics.create(TEST_USER_ID, "Operating Systems");

            mvc.perform(authorised(get(PATH)))
                    .andExpect(jsonPath("$[0].id").isNumber())
                    .andExpect(jsonPath("$[0].slug").value("operating-systems"))
                    // ISO-8601, not epoch millis. The Android client parses this.
                    .andExpect(jsonPath("$[0].createdAt").isString())
                    .andExpect(jsonPath("$[0].userId").doesNotExist());
        }
    }

    @Nested
    @DisplayName("POST /topics")
    class Create {

        private static final String OPERATING_SYSTEMS = """
                {"name": "Operating Systems"}""";

        @Test
        @DisplayName("answers 201 with the created topic")
        void createsATopic() throws Exception {
            mvc.perform(authorised(post(PATH)).contentType(MediaType.APPLICATION_JSON)
                            .content(OPERATING_SYSTEMS))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").isNumber())
                    .andExpect(jsonPath("$.name").value("Operating Systems"))
                    .andExpect(jsonPath("$.slug").value("operating-systems"));
        }

        @Test
        @DisplayName("stores it under the key's owner, not anyone the body names")
        void ownerComesFromTheKey() throws Exception {
            mvc.perform(authorised(post(PATH)).contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name": "Operating Systems", "userId": "someone-else"}"""))
                    .andExpect(status().isCreated());

            // If the body's userId had been honoured this would be empty.
            mvc.perform(authorised(get(PATH))).andExpect(jsonPath("$.length()").value(1));
        }

        @Test
        @DisplayName("rejects a blank name as 400 without reaching the service")
        void rejectsABlankName() throws Exception {
            mvc.perform(authorised(post(PATH)).contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name": "   "}"""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        @DisplayName("rejects a name too long for the column as 400, not 500")
        void rejectsAnOverlongName() throws Exception {
            String tooLong = "x".repeat(121);

            mvc.perform(authorised(post(PATH)).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\": \"" + tooLong + "\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("reports a duplicate slug as 409 with the slug that clashed")
        void reportsADuplicateAs409() throws Exception {
            topics.create(TEST_USER_ID, "Operating Systems");

            mvc.perform(authorised(post(PATH)).contentType(MediaType.APPLICATION_JSON)
                            // A different name that slugifies the same way.
                            .content("""
                                    {"name": "operating systems!"}"""))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.slug").value("operating-systems"));
        }

        @Test
        @DisplayName("reports a name with nothing sluggable in it as 400")
        void rejectsAnUnsluggableName() throws Exception {
            mvc.perform(authorised(post(PATH)).contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name": "!!!"}"""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Validation failed"));
        }
    }

    @Test
    @DisplayName("requires the API key like every other route under /api")
    void requiresTheApiKey() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
    }
}
