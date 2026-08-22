package dev.vsdeadshot.flashcards.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import dev.vsdeadshot.flashcards.support.EmbeddedPostgresTest;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Which of this application's routes answer without a credential.
 *
 * <p><strong>This is the control, not the code.</strong> An exemption expressed as a path prefix
 * is convenient precisely because a new route can join it without anyone deciding that it
 * should, so the guard cannot be the prefix itself — it has to be an assertion about the routes
 * that actually exist. A controller added under {@code /api/v1/auth/} fails this test rather
 * than shipping open.
 *
 * <p>It reads the real handler mappings out of the running context instead of a list written
 * here, so a route that exists and was never considered still shows up.
 */
@AutoConfigureMockMvc
@DisplayName("The unauthenticated routes")
class PublicRoutesTest extends EmbeddedPostgresTest {

    /**
     * Every route reachable without authenticating. Adding to this set is a deliberate act, and
     * the diff is the review.
     */
    private static final Set<String> EXPECTED_PUBLIC = Set.of(
            "/health",
            "/api/v1/auth/login",
            // Both are how a credential is obtained or given up, so both must answer without
            // one. Refresh especially: the case it exists for is the one where the access token
            // has already expired.
            "/api/v1/auth/refresh",
            "/api/v1/auth/logout",
            // Spring Boot's own error dispatch target, which this application does not declare
            // and cannot remove without giving up the error handling every other route relies
            // on. It is listed because this test found it rather than because anyone chose it,
            // which is the point of enumerating real routes instead of trusting the prefix.
            // What it may say is pinned separately by errorDisclosesNothing().
            "/error");

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    @Autowired
    private MockMvc mvc;

    private Set<String> mappedPaths() {
        Set<String> paths = new TreeSet<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            if (info.getPathPatternsCondition() != null) {
                info.getPathPatternsCondition().getPatternValues().forEach(paths::add);
            }
        }
        return paths;
    }

    @Test
    @DisplayName("are exactly the five that have a reason to be")
    void areExactlyTheExpectedOnes() {
        Set<String> actuallyPublic = new TreeSet<>();
        for (String path : mappedPaths()) {
            if (PublicRoutes.isPublic(path)) {
                actuallyPublic.add(path);
            }
        }

        assertEquals(new TreeSet<>(EXPECTED_PUBLIC), actuallyPublic,
                "a route became reachable without a credential, or one that should be is not. "
                        + "Both directions are a decision, not an accident to absorb");
    }

    @Test
    @DisplayName("do not include anything that reads or writes a user's data")
    void noDataRouteIsPublic() {
        for (String path : mappedPaths()) {
            if (path.startsWith("/api/v1/") && !path.startsWith("/api/v1/auth/")) {
                assertFalse(PublicRoutes.isPublic(path),
                        path + " serves a user's own data and must never answer unauthenticated");
            }
        }
    }

    /**
     * {@code /error} is unauthenticated and cannot practically be otherwise, so what matters is
     * that reaching it directly is worth nothing. Boot defaults to omitting the message, the
     * stack trace and binding errors; this asserts that rather than trusting it, because a
     * property set for debugging one afternoon would turn this route into the leak that
     * {@code ApiExceptionHandler} is careful never to become.
     */
    @Test
    @DisplayName("include Boot's error route, which discloses nothing when reached directly")
    void errorDisclosesNothing() throws Exception {
        String body = mvc.perform(get("/error"))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("\"trace\""), "a stack trace must never reach a caller");
        assertFalse(body.contains("\"message\""), "nor the exception's own message");
        assertFalse(body.contains("dev.vsdeadshot"), "nor any class name from this application");
    }

    /**
     * The prefix check has to be a prefix check and not a contains check. A path that merely
     * mentions the auth segment somewhere in the middle is an ordinary route.
     */
    @Test
    @DisplayName("are matched by prefix, so a lookalike path is not exempted")
    void aLookalikePathIsNotPublic() {
        assertFalse(PublicRoutes.isPublic("/api/v1/cards/api/v1/auth/login"),
                "the auth prefix must anchor at the start, or any path could carry it");
        assertFalse(PublicRoutes.isPublic("/api/v1/authorised-things"),
                "the trailing slash is what stops a longer segment matching the auth prefix");
        assertTrue(PublicRoutes.isPublic("/actuator/anything"),
                "anything outside /api was never guarded, so it is public by construction");
    }
}
