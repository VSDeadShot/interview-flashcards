package dev.vsdeadshot.flashcards.data.remote;

import dev.vsdeadshot.flashcards.data.remote.dto.LoginRequestDto;
import dev.vsdeadshot.flashcards.data.remote.dto.RefreshRequestDto;
import dev.vsdeadshot.flashcards.data.remote.dto.TokenResponseDto;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.Headers;
import retrofit2.http.POST;

/**
 * The three routes that exist to obtain and give up a token.
 *
 * <p>Separate from {@link FlashcardsApi} because it is served by a different client. Everything
 * in {@code FlashcardsApi} goes through the interceptor that attaches a bearer token and the
 * authenticator that renews one; if refreshing went through that client too, a refresh that came
 * back {@code 401} would trigger a refresh. These three are the only calls in the app that carry
 * no credential of their own, so they are the only ones that can safely be the way out.
 */
public interface AuthApi {

    /**
     * Allowed 150 seconds to answer, where every other call gets twenty.
     *
     * <p>Signing in is the call a person makes right after opening the app, so it is the one that
     * meets a Render instance that has been put to sleep — and waking one takes about two minutes.
     * At the default it gave up before the server could answer, every time. Only this call waits
     * that long: renewal and logout keep the default, since a slow answer to either already costs
     * nothing, and a longer wait would only delay finding that out. The header is consumed by
     * {@link TimeoutInterceptor} and never reaches the server.
     */
    @Headers(TimeoutInterceptor.HEADER + ": 150")
    @POST("auth/login")
    Call<TokenResponseDto> login(@Body LoginRequestDto body);

    @POST("auth/refresh")
    Call<TokenResponseDto> refresh(@Body RefreshRequestDto body);

    /** {@code 204} whether or not the server recognised the token. */
    @POST("auth/logout")
    Call<Void> logout(@Body RefreshRequestDto body);
}
