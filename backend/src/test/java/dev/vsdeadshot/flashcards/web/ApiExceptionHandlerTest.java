package dev.vsdeadshot.flashcards.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.vsdeadshot.flashcards.service.DuplicateTopicException;
import dev.vsdeadshot.flashcards.service.NotFoundException;
import dev.vsdeadshot.flashcards.support.EmbeddedPostgresTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Drives the advice through a controller that exists only here, because the real ones do not
 * exist yet. That is not merely a stopgap: it keeps these assertions about the mapping from
 * exception to response and nothing else, so they will not start failing for reasons that
 * belong to a controller.
 */
@AutoConfigureMockMvc
@Import(ApiExceptionHandlerTest.ThrowingController.class)
class ApiExceptionHandlerTest extends EmbeddedPostgresTest {

    @Autowired
    private MockMvc mvc;

    /** Every path here is under {@code /api/}, so the key is what gets past the filter. */
    private static MockHttpServletRequestBuilder request(String path) {
        return get(ThrowingController.BASE + path).header(ApiKeyFilter.HEADER, TEST_API_KEY);
    }

    @Nested
    @DisplayName("the service layer's exceptions")
    class ServiceExceptions {

        @Test
        @DisplayName("report a missing row as 404")
        void notFoundBecomes404() throws Exception {
            mvc.perform(request("/not-found"))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.title").value("Not found"))
                    .andExpect(jsonPath("$.detail").value("card 7 was not found"));
        }

        @Test
        @DisplayName("report a clashing topic slug as 409")
        void duplicateTopicBecomes409() throws Exception {
            mvc.perform(request("/duplicate"))
                    .andExpect(status().isConflict())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.title").value("Duplicate topic"));
        }

        @Test
        @DisplayName("hand the clashing slug back as a field, not only inside the sentence")
        void duplicateTopicCarriesTheSlug() throws Exception {
            mvc.perform(request("/duplicate"))
                    .andExpect(jsonPath("$.slug").value("operating-systems"));
        }

        @Test
        @DisplayName("report input the service rejected as 400")
        void illegalArgumentBecomes400() throws Exception {
            mvc.perform(request("/invalid"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.title").value("Validation failed"))
                    .andExpect(jsonPath("$.detail").value("confidence must be between 1 and 5, was 7"));
        }
    }

    @Nested
    @DisplayName("Spring's own failures")
    class FrameworkExceptions {

        /**
         * Pins {@code spring.mvc.problemdetails.enabled}. Without it this request still fails
         * with {@code 400}, but in Boot's generic error format — same status, different body,
         * which is exactly the kind of drift a status-only assertion would miss.
         */
        @Test
        @DisplayName("use the same problem+json format as the advice")
        void missingParameterIsAlsoProblemJson() throws Exception {
            mvc.perform(request("/needs-a-parameter"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(400));
        }
    }

    @Test
    @DisplayName("never runs before the key is checked")
    void theFilterStillComesFirst() throws Exception {
        // Without a key this is a 401, not the 404 the handler would produce. The advice sits
        // behind the dispatcher, so an unauthenticated caller learns nothing from it.
        mvc.perform(get(ThrowingController.BASE + "/not-found"))
                .andExpect(status().isUnauthorized());
    }

    @RestController
    @RequestMapping(ThrowingController.BASE)
    static class ThrowingController {

        static final String BASE = "/api/test-errors";

        @GetMapping("/not-found")
        void notFound() {
            throw new NotFoundException("card", 7);
        }

        @GetMapping("/duplicate")
        void duplicate() {
            throw new DuplicateTopicException("operating-systems");
        }

        @GetMapping("/invalid")
        void invalid() {
            throw new IllegalArgumentException("confidence must be between 1 and 5, was 7");
        }

        @GetMapping("/needs-a-parameter")
        void needsParameter(@RequestParam int limit) {
        }
    }
}
