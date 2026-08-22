package dev.vsdeadshot.flashcards.web;

/**
 * Which paths are reachable without authenticating.
 *
 * <p>One place, consulted by every filter that guards a request, because the alternative -- each
 * filter carrying its own idea of what is exempt -- is how a route ends up open in one and
 * closed in another. {@code PublicRoutesTest} enumerates the application's real routes against
 * this rather than testing it in the abstract, so an endpoint added under a public prefix by
 * accident fails a test instead of shipping.
 *
 * <p>Two exemptions, for two different reasons. {@code /health} is not under the API at all: a
 * platform's probe cannot present a credential, and a health check answering {@code 401} reads
 * as an instance that never became healthy. The auth routes are under it and are exempt because
 * they are how a credential is obtained -- requiring one to ask for one is a closed loop.
 */
final class PublicRoutes {

    /** Everything the API serves. Anything outside it was never guarded to begin with. */
    private static final String API_PREFIX = "/api/";

    /**
     * Sign-in and, shortly, refresh and sign-out. A prefix rather than an exact path, so the
     * routes that join it are exempt by living here -- which is also the risk, and why the test
     * asserts on the actual mapped routes rather than on this constant.
     */
    private static final String AUTH_PREFIX = "/api/v1/auth/";

    private PublicRoutes() {
    }

    static boolean isPublic(String uri) {
        return !uri.startsWith(API_PREFIX) || uri.startsWith(AUTH_PREFIX);
    }
}
