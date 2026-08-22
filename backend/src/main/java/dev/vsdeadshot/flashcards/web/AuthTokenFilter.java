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
 * Authenticates a request presenting {@code Authorization: Bearer}.
 *
 * <p>Runs ahead of {@link ApiKeyFilter} and publishes the same {@code userId} attribute, so
 * every controller, service and repository behind it is untouched by which credential arrived.
 * That seam is the whole reason this change is small.
 *
 * <p><strong>A request with no bearer token falls through rather than being refused.</strong>
 * The API key still authenticates while it exists, and this filter has no opinion about a
 * request that is not addressed to it. A request that *does* carry a bearer token and fails is
 * refused here and does not fall through -- presenting a broken token and then being let in on
 * a key would make the outcome depend on the order two credentials happened to be checked.
 */
@Component
@Order(AuthTokenFilter.ORDER)
public class AuthTokenFilter extends OncePerRequestFilter {

    /**
     * Ahead of {@link ApiKeyFilter}, so the newer credential is the one consulted first and the
     * older one only ever sees what the newer one declined to handle. Stated as a number
     * relative to nothing, unlike {@code RequestSizeLimitFilter}, because this has to come
     * first and there is nothing above it to be relative to.
     */
    public static final int ORDER = 5;

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
            chain.doFilter(request, response);
            return;
        }

        Optional<String> userId = tokens.authenticate(header.substring(PREFIX.length()).trim());
        if (userId.isEmpty()) {
            // No body, exactly as the key filter answers. Unknown, expired and revoked are one
            // response on purpose: a caller told which of those applied learns something about
            // a token they failed to present correctly.
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        request.setAttribute(ApiKeyFilter.USER_ID_ATTRIBUTE, userId.get());
        chain.doFilter(request, response);
    }
}
