package dev.vsdeadshot.flashcards.web;

import dev.vsdeadshot.flashcards.config.FlashcardsProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects any request to {@code /api/**} that does not present the shared secret.
 *
 * <p>Runs before the dispatcher, so an unauthenticated caller cannot learn which paths exist:
 * a wrong key gets {@code 401} whether the endpoint is real or not.
 */
@Component
@Order(ApiKeyFilter.ORDER)
public class ApiKeyFilter extends OncePerRequestFilter {

    /**
     * First of this application's filters, and stated rather than left to chance. Two filter
     * beans with no order between them are ordered arbitrarily, so the guarantee that an
     * unauthenticated request is refused before anything else looks at it would otherwise rest
     * on nothing. {@link RequestSizeLimitFilter} places itself relative to this.
     */
    public static final int ORDER = 10;

    public static final String HEADER = "X-API-Key";

    /**
     * Where the authenticated owner is published for controllers to pick up with
     * {@code @RequestAttribute}. This is the seam a real subject claim will arrive through,
     * which is why controllers read it from the request rather than from configuration.
     */
    public static final String USER_ID_ATTRIBUTE = "userId";

    private static final String PROTECTED_PREFIX = "/api/";

    private final byte[] expectedKey;
    private final String userId;

    public ApiKeyFilter(FlashcardsProperties properties) {
        this.expectedKey = properties.apiKey().getBytes(StandardCharsets.UTF_8);
        this.userId = properties.userId();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PROTECTED_PREFIX);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String presented = request.getHeader(HEADER);
        if (presented == null || !matches(presented)) {
            // No body, per the contract. There is nothing useful to say to a caller who did
            // not authenticate, and a message would only describe the check it failed.
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        request.setAttribute(USER_ID_ATTRIBUTE, userId);
        chain.doFilter(request, response);
    }

    /**
     * Compared with {@link MessageDigest#isEqual} rather than {@code String.equals}, which
     * returns as soon as two bytes differ and so leaks how much of a guess was correct.
     */
    private boolean matches(String presented) {
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), expectedKey);
    }
}
