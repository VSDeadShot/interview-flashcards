package dev.vsdeadshot.flashcards.data;

import android.util.Log;
import dev.vsdeadshot.flashcards.data.auth.TokenStore;
import dev.vsdeadshot.flashcards.data.auth.TokenStore.AuthState;
import dev.vsdeadshot.flashcards.data.auth.TokenStore.SignedOutReason;
import dev.vsdeadshot.flashcards.data.remote.AuthApi;
import dev.vsdeadshot.flashcards.data.remote.dto.LoginRequestDto;
import dev.vsdeadshot.flashcards.data.remote.dto.RefreshRequestDto;
import dev.vsdeadshot.flashcards.data.remote.dto.TokenResponseDto;
import java.io.IOException;

/**
 * Signing in and signing out. Blocking, like every other repository here, and run on
 * {@code Graph.io()} by whatever is calling it.
 *
 * <p>The one repository in this app that does not read or write the cache. It is here rather
 * than in {@code data.remote} because it is the seam a screen talks to, and because it is the
 * only place that both makes a request and changes what is stored about the session — the two
 * halves the rest of the app is deliberately kept away from.
 *
 * <p><strong>Renewing is not here.</strong> That happens in {@code TokenAuthenticator}, below
 * every caller, because it is triggered by a response rather than by anybody deciding to do it.
 */
public final class AuthRepository {

    private static final String TAG = "AuthRepository";

    private final AuthApi api;
    private final TokenStore tokens;

    public AuthRepository(AuthApi api, TokenStore tokens) {
        this.api = api;
        this.tokens = tokens;
    }

    public AuthState state() {
        return tokens.state();
    }

    /**
     * Exchanges the passphrase for a pair of tokens.
     *
     * @throws dev.vsdeadshot.flashcards.data.remote.ApiException the server refused it —
     *     {@code 401} for a wrong passphrase, {@code 429} for too many attempts, {@code 503}
     *     for an instance with no passphrase configured at all
     * @throws IOException the server could not be reached
     */
    public void signIn(String passphrase) throws IOException {
        TokenResponseDto issued = api.login(new LoginRequestDto(passphrase)).execute().body();
        if (issued == null || issued.accessToken == null || issued.refreshToken == null) {
            // A 2xx that is not a token pair. Treated as a failed sign-in rather than stored in
            // part, because half a pair is worse than none: a refresh token without its access
            // token would present itself on the next request and be spent for nothing.
            throw new IOException("Sign-in returned no token pair");
        }
        tokens.save(issued.accessToken, issued.refreshToken);
    }

    /**
     * Ends the session on this device and, if it can be reached, on the server.
     *
     * <p>The local half happens either way and happens <em>last</em>. Telling the server first
     * means a network that dies mid-request still leaves this device signed out, which is what
     * somebody who pressed sign out asked for; doing it the other way round would leave the
     * refresh token alive on a server nobody was going to tell about it.
     *
     * <p>The failure is swallowed on purpose. There is nothing a person can do about it and
     * nothing they would do differently — the token expires within thirty days regardless, and
     * offering to retry a sign-out is a worse screen than not offering one.
     */
    public void signOut() {
        String refreshToken = tokens.refreshToken();
        if (refreshToken != null) {
            try {
                api.logout(new RefreshRequestDto(refreshToken)).execute();
            } catch (IOException e) {
                Log.w(TAG, "Could not tell the server about the sign-out; clearing anyway");
            }
        }
        tokens.signOut(SignedOutReason.SIGNED_OUT);
    }
}
