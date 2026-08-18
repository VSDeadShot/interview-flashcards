package dev.vsdeadshot.flashcards.config;

import dev.vsdeadshot.flashcards.ai.GeminiClient;
import dev.vsdeadshot.flashcards.ai.GeminiRestClient;
import dev.vsdeadshot.flashcards.ai.UnconfiguredGeminiClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class GeminiConfiguration {

    /**
     * Which client exists is decided once, here, rather than checked on every call: a caller
     * asking for a client should not also have to ask whether there is one.
     *
     * <p>Transport tuning lives here rather than inside {@code GeminiRestClient} for a reason
     * worth keeping. A client that sets its own request factory silently replaces the one
     * {@code MockRestServiceServer} installs, which turns every unit test of it into a live call
     * to the real API — passing tests, real quota, no warning.
     *
     * <p>45 seconds sits under the Android client's 60, so this side gives up first. A server
     * still working after its caller has gone is doing billable work nobody will ever see.
     *
     * <p>The builder is constructed here rather than injected: this classpath has no
     * auto-configured {@code RestClient.Builder}, and pulling in a starter to get one would be a
     * dependency bought for a single caller that shares it with nothing.
     */
    @Bean
    public GeminiClient geminiClient(GeminiProperties properties) {
        if (!properties.configured()) {
            return new UnconfiguredGeminiClient();
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(45));
        return new GeminiRestClient(
                RestClient.builder().requestFactory(factory),
                properties.apiKey(),
                properties.model());
    }
}
