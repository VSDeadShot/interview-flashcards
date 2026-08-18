package dev.vsdeadshot.flashcards.data.remote;

import com.squareup.moshi.Moshi;
import dev.vsdeadshot.flashcards.BuildConfig;
import java.time.Duration;
import okhttp3.OkHttpClient;
import retrofit2.Retrofit;
import retrofit2.converter.moshi.MoshiConverterFactory;

/**
 * Builds the one {@link FlashcardsApi} the app talks to.
 *
 * <p>The base URL and key come from {@code BuildConfig}, which reads them from
 * {@code local.properties} at build time — gitignored, so neither is ever written down where
 * the repository can see it. The two-argument form exists so tests can point the same client,
 * built the same way, at a loopback server.
 */
public final class ApiClient {

    private ApiClient() {
    }

    public static FlashcardsApi create() {
        return create(BuildConfig.BASE_URL, BuildConfig.API_KEY);
    }

    public static FlashcardsApi create(String baseUrl, String apiKey) {
        Moshi moshi = new Moshi.Builder().add(new JsonTimeAdapters()).build();

        OkHttpClient http = new OkHttpClient.Builder()
                .addInterceptor(new ApiKeyInterceptor(apiKey))
                // After the key, so the response this one inspects is the response to a request
                // that actually carried one.
                .addInterceptor(new ProblemInterceptor(moshi))
                // Last, so a call asking for a longer read timeout gets it applied to the whole
                // chain below rather than being cut short while its failure is being classified.
                .addInterceptor(new TimeoutInterceptor())
                // Short enough that a sync on a dead network gives up while the user is still
                // looking at the screen, long enough for a phone waking its radio.
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(20))
                .writeTimeout(Duration.ofSeconds(20))
                .build();

        return new Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(http)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(FlashcardsApi.class);
    }
}
