package dev.vsdeadshot.flashcards.data.remote;

import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import dev.vsdeadshot.flashcards.data.auth.TokenStore;
import dev.vsdeadshot.flashcards.data.auth.TokenStore.SignedOutReason;
import dev.vsdeadshot.flashcards.data.remote.dto.RefreshRequestDto;
import dev.vsdeadshot.flashcards.data.remote.dto.TokenResponseDto;
import java.io.IOException;
import okhttp3.Authenticator;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.Route;

/**
 * Renews an expired access token when a request comes back {@code 401}, and retries it.
 *
 * <p>OkHttp's own seam for this, rather than a hand-rolled retry in an interceptor. It runs
 * inside {@code RetryAndFollowUpInterceptor}, which sits <em>below</em> the application
 * interceptors — which has two consequences worth stating, because both are load-bearing:
 *
 * <ul>
 *   <li>{@link ProblemInterceptor} never sees the {@code 401} that was recovered from. It is an
 *       application interceptor, so it only ever inspects the final response, and a renewal that
 *       worked is invisible to every caller. That is exactly what makes this possible to add
 *       without touching the nine methods of {@link FlashcardsApi} or the sync engine that
 *       drives them.
 *   <li>The retried request does <em>not</em> pass back through {@link AuthInterceptor}. The
 *       follow-up returned here is what is sent, so this class has to set the header itself.
 * </ul>
 *
 * <p>Returning null means "I have nothing better to offer", and the {@code 401} then travels up
 * to {@code ProblemInterceptor} and becomes an {@code ApiException} whose disposition is
 * {@code STOP}. That is the honest answer: the request cannot be authenticated, and no amount
 * of retrying by the outbox will change it.
 */
final class TokenAuthenticator implements Authenticator {

    private static final String TAG = "TokenAuthenticator";

    /**
     * How many attempts one call may make before this gives up. Two: the original, and one
     * retry with a renewed token. A third would mean the server rejected a token it had just
     * issued, and a loop that keeps asking is how a client turns a server-side problem into a
     * flat battery.
     */
    private static final int MAX_ATTEMPTS = 2;

    private final TokenStore tokens;
    private final AuthApi auth;

    TokenAuthenticator(TokenStore tokens, AuthApi auth) {
        this.tokens = tokens;
        this.auth = auth;
    }

    @Nullable
    @Override
    public Request authenticate(@Nullable Route route, @NonNull Response response) {
        if (attempts(response) >= MAX_ATTEMPTS) {
            return null;
        }

        // Synchronized so two calls failing at once make one refresh between them rather than
        // two. The second would present a refresh token the first has already spent, which the
        // server is right to read as a copy in circulation — and it would answer by revoking the
        // family, signing this device out over nothing but its own concurrency.
        synchronized (this) {
            String current = tokens.accessToken();
            if (current != null
                    && !AuthInterceptor.bearer(current)
                            .equals(response.request().header(AuthInterceptor.HEADER))) {
                // Somebody else refreshed while this request was in flight. Its 401 is stale.
                return retryWith(response, current);
            }

            String refreshToken = tokens.refreshToken();
            if (refreshToken == null) {
                // Never signed in, or signed out already. Nothing to renew and nothing to
                // report — state() already says why, and overwriting the reason here would turn
                // a deliberate sign-out into an expired session on the next sync.
                return null;
            }
            return renew(response, refreshToken);
        }
    }

    @Nullable
    private Request renew(Response response, String refreshToken) {
        try {
            TokenResponseDto issued =
                    auth.refresh(new RefreshRequestDto(refreshToken)).execute().body();
            if (issued == null || issued.accessToken == null || issued.refreshToken == null) {
                // A 2xx that is not a token pair. Nothing to store, and storing half of one
                // would leave a spent refresh token on the device.
                Log.w(TAG, "Refresh returned no token pair");
                return null;
            }
            if (!tokens.saveIfCurrent(refreshToken, issued.accessToken, issued.refreshToken)) {
                // The session this renewal belonged to ended while it was on the network — a
                // sign-out, or a sign-out and a fresh sign-in. Writing the pair would undo that,
                // so it is dropped, and so is the retry that would have spent it.
                return null;
            }
            return retryWith(response, issued.accessToken);
        } catch (ApiException e) {
            if (e.status() == 401) {
                // The refresh token is finished: expired, revoked by a logout elsewhere, or
                // presented twice and treated as stolen. The server does not say which, on
                // purpose, and a client would do the same thing about all three — unless the
                // session already ended here while this was in flight, in which case the refusal
                // is about a token the device no longer holds and must not relabel that.
                tokens.signOutIfCurrent(refreshToken, SignedOutReason.SESSION_EXPIRED);
            }
            // Any other status is the server having a bad moment. The tokens are left alone so
            // the next sync tries again rather than sending somebody to a sign-in screen over a
            // 503.
            return null;
        } catch (IOException e) {
            // The network died between the 401 and the refresh. Same reasoning: keep the tokens.
            return null;
        }
    }

    private static Request retryWith(Response response, String accessToken) {
        return response.request()
                .newBuilder()
                .header(AuthInterceptor.HEADER, AuthInterceptor.bearer(accessToken))
                .build();
    }

    /** How many times this call has already been sent, counting the response in hand. */
    private static int attempts(Response response) {
        int count = 1;
        for (Response prior = response.priorResponse();
                prior != null;
                prior = prior.priorResponse()) {
            count++;
        }
        return count;
    }
}
