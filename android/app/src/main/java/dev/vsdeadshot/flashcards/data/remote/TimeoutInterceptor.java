package dev.vsdeadshot.flashcards.data.remote;

import androidx.annotation.NonNull;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Lets one call ask for a longer read timeout than the client's default.
 *
 * <p>Generation blocks for as long as a model takes, which is well past the twenty seconds every
 * other call here needs. Raising the client's default would relax it for calls that should stay
 * strict, and a second Retrofit instance would mean two clients to keep configured identically.
 * A header consumed here keeps one client and declares the exception on the method that needs it.
 *
 * <p>The header is ours and is stripped before the request goes out — the server has no use for
 * it, and sending it would invite somebody to think it means something upstream.
 */
public final class TimeoutInterceptor implements Interceptor {

    static final String HEADER = "X-Read-Timeout-Seconds";

    @NonNull
    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        Request request = chain.request();
        String requested = request.header(HEADER);
        if (requested == null) {
            return chain.proceed(request);
        }
        Request stripped = request.newBuilder().removeHeader(HEADER).build();
        return chain.withReadTimeout(Integer.parseInt(requested), TimeUnit.SECONDS)
                .proceed(stripped);
    }
}
