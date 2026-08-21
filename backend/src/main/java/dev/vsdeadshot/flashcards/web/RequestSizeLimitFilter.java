package dev.vsdeadshot.flashcards.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Caps how much body an API request may carry, before anything reads it.
 *
 * <p>Bean Validation cannot do this job. {@code CardRequest}'s {@code @Size(max = 10_000)} fires
 * <em>after</em> binding, so a 30MB body is read off the socket and materialised into a Java
 * {@code String} — roughly 60MB of heap — and only then rejected as too long. The annotation
 * describes what a card may contain; this describes what the server will accept off the wire,
 * and only the second one bounds memory.
 *
 * <p>Ordered <strong>after</strong> {@link ApiKeyFilter} on purpose. A request with no key is
 * already refused before the servlet reads a byte, so the unauthenticated case costs nothing
 * either way — and running this first would answer an unauthenticated caller {@code 413} where
 * the contract says every unauthenticated request looks alike. Keeping the key check first means
 * "no key, always {@code 401}" stays literally true.
 */
@Component
@Order(RequestSizeLimitFilter.ORDER)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    public static final int ORDER = ApiKeyFilter.ORDER + 10;

    /**
     * Comfortably above anything legitimate and far below anything dangerous.
     *
     * <p>The largest real request is a card with both fields at their {@code @Size} limit: 10,000
     * characters each. JSON escaping costs at worst six bytes per character, so 120KB is the
     * ceiling a valid card can reach — this leaves better than twice that in headroom while
     * keeping one request's body a rounding error against the heap.
     */
    static final int MAX_BYTES = 256 * 1024;

    private static final String PROTECTED_PREFIX = "/api/";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PROTECTED_PREFIX);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        long declared = request.getContentLengthLong();
        if (declared > MAX_BYTES) {
            tooLarge(response);
            return;
        }

        // A declared length is authoritative — the container will not hand over more body than
        // Content-Length promises — so a request that passed the check above needs no watching.
        //
        // A chunked request declares nothing and reports -1, which is exactly how a check on
        // Content-Length alone gets walked past: one header and the cap is gone. Those are
        // wrapped so the limit is enforced as the body is read instead. Nothing this API talks
        // to sends chunked today, but a reverse proxy with request buffering off produces it,
        // so the case is closed rather than assumed away.
        chain.doFilter(declared < 0 ? new LimitedRequest(request) : request, response);
    }

    /**
     * Written by hand rather than raised as an exception for {@link ApiExceptionHandler}: a
     * filter runs outside the {@code DispatcherServlet}, so no {@code @RestControllerAdvice} will
     * ever see what is thrown here. The shape still matches every other error this API returns,
     * because a client should not need a second parser for this one.
     */
    private static void tooLarge(HttpServletResponse response) throws IOException {
        // CONTENT_TOO_LARGE rather than PAYLOAD_TOO_LARGE: same 413, but RFC 9110 renamed the
        // reason phrase and Spring deprecated the old constant to match.
        response.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(
                "{\"type\":\"about:blank\",\"title\":\"Content too large\",\"status\":"
                        + HttpStatus.CONTENT_TOO_LARGE.value()
                        + ",\"detail\":\"The request body must not exceed " + MAX_BYTES
                        + " bytes.\"}");
    }

    /** Signals a body that ran past the cap while being read. */
    private static final class BodyTooLargeException extends IOException {
        BodyTooLargeException() {
            super("request body exceeded " + MAX_BYTES + " bytes");
        }
    }

    /**
     * Enforces the cap on a body whose length was not declared.
     *
     * <p>Failing part-way through a read costs the clean {@code 413}: the exception surfaces
     * inside Spring's message converter and is reported as {@code 400 Failed to read request}.
     * That is a worse message and the right outcome — memory stays bounded, nothing about the
     * server leaks, and the precise status stays reserved for the case that can be answered
     * before a byte is touched.
     */
    private static final class LimitedRequest extends HttpServletRequestWrapper {

        LimitedRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            ServletInputStream delegate = super.getInputStream();
            return new ServletInputStream() {

                private long read;

                private void count(long bytes) throws IOException {
                    read += bytes;
                    if (read > MAX_BYTES) {
                        throw new BodyTooLargeException();
                    }
                }

                @Override
                public int read() throws IOException {
                    int b = delegate.read();
                    if (b != -1) {
                        count(1);
                    }
                    return b;
                }

                // Overridden as well as read(): InputStream's default implementation of this
                // loops over the single-byte read, which is correct and reads a large body one
                // byte at a time. The counting has to happen in both or it happens in neither.
                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    int n = delegate.read(buffer, offset, length);
                    if (n > 0) {
                        count(n);
                    }
                    return n;
                }

                @Override
                public boolean isFinished() {
                    return delegate.isFinished();
                }

                @Override
                public boolean isReady() {
                    return delegate.isReady();
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    delegate.setReadListener(listener);
                }
            };
        }

        /** Kept consistent with {@link #getInputStream()}; a reader must not skip the cap. */
        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            return new BufferedReader(new InputStreamReader(getInputStream(),
                    encoding == null ? StandardCharsets.UTF_8.name() : encoding));
        }
    }
}
