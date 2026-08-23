package dev.vsdeadshot.flashcards.data.remote;

import android.content.Context;
import com.squareup.moshi.Moshi;
import dev.vsdeadshot.flashcards.BuildConfig;
import dev.vsdeadshot.flashcards.data.auth.PrefsTokenStore;
import dev.vsdeadshot.flashcards.data.auth.TokenStore;
import java.time.Duration;
import okhttp3.OkHttpClient;
import retrofit2.Retrofit;
import retrofit2.converter.moshi.MoshiConverterFactory;

/**
 * Builds the two clients this app talks to the server with.
 *
 * <p>Two, not one, and the split is the whole design. {@link FlashcardsApi} carries a bearer
 * token and renews it when the server says it is finished; {@link AuthApi} is how one is
 * obtained in the first place and therefore carries nothing. Building the second on the first
 * would mean a refused refresh triggering a refresh.
 *
 * <p>The base URL comes from {@code BuildConfig}, which reads it from {@code local.properties}
 * at build time and otherwise defaults to the deployed instance. There is no longer a key to
 * read: the credential is a token somebody signs in for, held in a {@link TokenStore}, and it
 * is never built into the APK. The {@code baseUrl} overloads exist so tests can point the same
 * clients, assembled the same way, at a loopback server.
 */
public final class ApiClient {

    private ApiClient() {
    }

    public static FlashcardsApi create(Context context) {
        return create(BuildConfig.BASE_URL, PrefsTokenStore.get(context));
    }

    public static FlashcardsApi create(String baseUrl, TokenStore tokens) {
        Moshi moshi = moshi();
        OkHttpClient http = shared(
                        // First, so every interceptor below it — and every response the one
                        // above it inspects — belongs to a request that actually carried a
                        // credential.
                        timeouts().addInterceptor(new AuthInterceptor(tokens)), moshi)
                // The authenticator, not another interceptor. It runs below the application
                // interceptors, inside OkHttp's own retry loop, which is what lets a recovered
                // 401 stay invisible to ProblemInterceptor and to every caller above it.
                .authenticator(new TokenAuthenticator(tokens, auth(baseUrl)))
                .build();
        return retrofit(baseUrl, http, moshi).create(FlashcardsApi.class);
    }

    public static AuthApi auth() {
        return auth(BuildConfig.BASE_URL);
    }

    /**
     * The bare client: problem parsing and timeouts, no credential and no renewal.
     *
     * <p>Its own {@code OkHttpClient} rather than a stripped copy of the other one. Sharing a
     * connection pool would be the only saving, and the cost would be a client whose
     * authenticator could reach the three routes that have to work without one.
     */
    public static AuthApi auth(String baseUrl) {
        Moshi moshi = moshi();
        return retrofit(baseUrl, shared(timeouts(), moshi).build(), moshi).create(AuthApi.class);
    }

    private static Moshi moshi() {
        return new Moshi.Builder().add(new JsonTimeAdapters()).build();
    }

    private static OkHttpClient.Builder timeouts() {
        // Short enough that a sync on a dead network gives up while the user is still looking
        // at the screen, long enough for a phone waking its radio.
        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(20))
                .writeTimeout(Duration.ofSeconds(20));
    }

    /** What both clients get, in the order both need it. */
    private static OkHttpClient.Builder shared(OkHttpClient.Builder builder, Moshi moshi) {
        return builder
                .addInterceptor(new ProblemInterceptor(moshi))
                // Last, so a call asking for a longer read timeout gets it applied to the whole
                // chain below rather than being cut short while its failure is being classified.
                .addInterceptor(new TimeoutInterceptor());
    }

    private static Retrofit retrofit(String baseUrl, OkHttpClient http, Moshi moshi) {
        return new Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(http)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build();
    }
}
