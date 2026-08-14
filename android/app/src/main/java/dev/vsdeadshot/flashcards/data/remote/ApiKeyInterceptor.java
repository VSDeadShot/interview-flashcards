package dev.vsdeadshot.flashcards.data.remote;

import java.io.IOException;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Attaches {@code X-API-Key} to every request, which is the only authentication the backend
 * has. When that becomes a bearer token this is the one class that changes.
 *
 * <p>A blank key is refused here, at construction, rather than sent. The alternative is every
 * request coming back {@code 401} and the sync stopping with an error that describes the
 * symptom and not the cause. This mirrors the backend's own posture on
 * {@code FLASHCARDS_DB_PASSWORD}: no silent default credential, and a failure that names what
 * is missing. It is not enforced at build time because the scheduler and database tests need
 * no key, and a fresh clone should still be able to run them.
 */
public final class ApiKeyInterceptor implements Interceptor {

    static final String HEADER = "X-API-Key";

    private final String apiKey;

    public ApiKeyInterceptor(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "No API key. Add flashcards.apiKey to android/local.properties — it is "
                            + "gitignored, and there is deliberately no default.");
        }
        this.apiKey = apiKey;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request().newBuilder().header(HEADER, apiKey).build();
        return chain.proceed(request);
    }
}
