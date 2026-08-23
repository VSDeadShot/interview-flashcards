package dev.vsdeadshot.flashcards.data.remote;

import dev.vsdeadshot.flashcards.data.remote.dto.LoginRequestDto;
import dev.vsdeadshot.flashcards.data.remote.dto.RefreshRequestDto;
import dev.vsdeadshot.flashcards.data.remote.dto.TokenResponseDto;
import retrofit2.Call;
import retrofit2.http.Body;
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

    @POST("auth/login")
    Call<TokenResponseDto> login(@Body LoginRequestDto body);

    @POST("auth/refresh")
    Call<TokenResponseDto> refresh(@Body RefreshRequestDto body);

    /** {@code 204} whether or not the server recognised the token. */
    @POST("auth/logout")
    Call<Void> logout(@Body RefreshRequestDto body);
}
