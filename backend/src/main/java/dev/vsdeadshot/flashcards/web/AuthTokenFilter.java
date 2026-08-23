package dev.vsdeadshot.flashcards.web;

import dev.vsdeadshot.flashcards.service.TokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects any request to {@code /api/**} that does not present a valid bearer token.
 *
 * <p>The only thing authenticating this API. The shared API key it ran alongside is gone: a
 * static secret that every client build had to carry is extractable by anyone holding a build,
 * cannot be revoked without shipping another, and identifies nobody. A token is obtained by
 * signing in, expires on its own, and can be withdrawn from the server.
 *
 * <p>Runs before the dispatcher, so an unauthenticated caller cannot learn which paths exist: a
 * bad token gets {@code 401} whether the endpoint is real or not.
 *
 * <p><strong>A request with no {@code Authorization} header is now refused rather than passed
 * on.</strong> That is the inversion this change turns on. While the key existed, a request
 * carrying no bearer token was simply not addressed to this filter and fell through to be judged
 * by the other one; with nothing behind it, falling through means serving an unauthenticated
 * request. The absent header and the bad one now get the same answer, which is also the honest
 * one — neither established who is calling.
 */
@Component
@Order(AuthTokenFilter.ORDER)
public class AuthTokenFilter extends OncePerRequestFilter {

    /**
     * First of this application's filters, and stated rather than left to chance. Two filter
     * beans with no order between them are ordered arbitrarily, so the guarantee that an
     * unauthenticated request is refused before anything else looks at it would otherwise rest
     * on nothing. {@link RequestSizeLimitFilter} places itself relative to this.
     */
    public static final int ORDER = 5;

    /**
     * Where the authenticated owner is published for controllers to pick up with
     * {@code @RequestAttribute}. This is the seam a real subject claim will arrive through,
     * which is why controllers read it from the request rather than from configuration — and
     * why swapping the credential underneath it changed no controller, service or repository.
     */
    public static final String USER_ID_ATTRIBUTE = "userId";

    private static final String PREFIX = "Bearer ";

    private final TokenService tokens;

    public AuthTokenFilter(TokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return PublicRoutes.isPublic(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(PREFIX)) {
            unauthorized(response);
            return;
        }

        Optional<String> userId = tokens.authenticate(header.substring(PREFIX.length()).trim());
        if (userId.isEmpty()) {
            unauthorized(response);
            return;
        }

        request.setAttribute(USER_ID_ATTRIBUTE, userId.get());
        chain.doFilter(request, response);
    }

    /**
     * No body, per the contract. Missing, malformed, unknown, expired and revoked are one
     * response on purpose: a caller told which of those applied learns something about a token
     * they failed to present, and there is nothing useful to say to somebody who did not
     * authenticate beyond the fact that they did not.
     */
    private static void unauthorized(HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }
}
