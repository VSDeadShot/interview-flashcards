package dev.vsdeadshot.flashcards.data.remote;

import dev.vsdeadshot.flashcards.data.auth.TokenStore;
import java.io.IOException;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Attaches {@code Authorization: Bearer} to every request, replacing the static API key this
 * client used to carry.
 *
 * <p>The key was refused at construction when it was missing, on the grounds that a build with
 * no credential should fail where the credential belongs rather than as a {@code 401} nine
 * layers away. <strong>A missing token is the opposite case and is deliberately allowed
 * through.</strong> Not being signed in is an ordinary state of this app, not a broken build:
 * every screen reads the cache, so somebody with a full deck studies, writes and archives
 * without a token, and only the sync needs one. The request goes out bare, comes back
 * {@code 401}, and {@link TokenAuthenticator} decides what that means.
 */
final class AuthInterceptor implements Interceptor {

    static final String HEADER = "Authorization";

    private final TokenStore tokens;

    AuthInterceptor(TokenStore tokens) {
        this.tokens = tokens;
    }

    /** The one place the header's format is written down, shared with the authenticator. */
    static String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        String access = tokens.accessToken();
        if (access == null) {
            return chain.proceed(chain.request());
        }
        Request request =
                chain.request().newBuilder().header(HEADER, bearer(access)).build();
        return chain.proceed(request);
    }
}
