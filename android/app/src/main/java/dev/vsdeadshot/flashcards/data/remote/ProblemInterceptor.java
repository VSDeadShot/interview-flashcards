package dev.vsdeadshot.flashcards.data.remote;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import dev.vsdeadshot.flashcards.data.remote.dto.ProblemDetail;
import java.io.IOException;
import okhttp3.Interceptor;
import okhttp3.Response;

/**
 * Turns every non-2xx response into an {@link ApiException} before Retrofit sees it.
 *
 * <p>Done here rather than at each call site because the alternative is nine methods that each
 * have to remember to check {@code isSuccessful()}, and the one that forgets reads a null body
 * as an empty result — a sync that silently empties the local cache because the server said
 * {@code 401}. An exception cannot be ignored by accident.
 *
 * <p>The trade is that a non-2xx body is no longer readable through Retrofit. Nothing needs it:
 * the only thing this API says in an error body is a problem detail, and that is parsed here.
 */
final class ProblemInterceptor implements Interceptor {

    private final JsonAdapter<ProblemDetail> problems;

    ProblemInterceptor(Moshi moshi) {
        this.problems = moshi.adapter(ProblemDetail.class);
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Response response = chain.proceed(chain.request());
        if (response.isSuccessful()) {
            return response;
        }
        try (Response failed = response) {
            throw ApiException.from(failed.code(), parse(failed.body().string()));
        }
    }

    /**
     * A failure that is not problem+json at all — a proxy returning an HTML error page, or a
     * truncated body — still has a status, and the status is what the disposition is decided
     * from. So an unparseable body costs the explanation and nothing else.
     */
    private ProblemDetail parse(String body) {
        try {
            return problems.fromJson(body);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
