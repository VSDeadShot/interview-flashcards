package dev.vsdeadshot.flashcards.ui.auth;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import dev.vsdeadshot.flashcards.R;
import dev.vsdeadshot.flashcards.data.AuthRepository;
import dev.vsdeadshot.flashcards.data.auth.TokenStore;
import dev.vsdeadshot.flashcards.data.auth.TokenStore.AuthState;
import dev.vsdeadshot.flashcards.data.remote.ApiException;
import dev.vsdeadshot.flashcards.data.remote.Failure;
import dev.vsdeadshot.flashcards.ui.Graph;
import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Whether there is a session, and the one request that starts or ends one.
 *
 * <p>Scoped to the activity by everything that uses it, so the toolbar and the sign-in screen
 * are looking at one instance rather than two that have to be kept in step.
 *
 * <p><strong>This is the only view model that does not watch Room.</strong> Whether somebody is
 * signed in is not in the database and deliberately never will be — it belongs to the device
 * rather than to the deck, and putting it in a table would put it inside the thing the sync
 * replaces. It watches the token store instead, which matters for a reason the rest of the app
 * does not have: the session can end while nobody is looking at a screen, in a background sync,
 * and the listener is how that reaches the toolbar rather than surfacing as a sync that has
 * quietly stopped working.
 */
public final class AuthViewModel extends AndroidViewModel {

    /**
     * One sign-in attempt. {@code error} is a string resource rather than a message, so this
     * class never writes user-facing copy.
     */
    public record SignInState(boolean running, boolean succeeded, @StringRes Integer error) {

        static final SignInState IDLE = new SignInState(false, false, null);
    }

    /**
     * Built inside the background task, not held as a field.
     *
     * <p>Constructing it constructs an OkHttp client and a Retrofit, and this view model is
     * created in {@code MainActivity.onCreate} — on the main thread, at every launch, to answer
     * a question that is only ever "is there a token". Signing in and out are the only two
     * things that need a client, and they are rare enough to pay for one when they happen. The
     * same shape {@code GenerateViewModel} uses, for a related reason.
     */
    private final Supplier<AuthRepository> repository;

    private final TokenStore tokens;
    private final Executor io;

    private final MutableLiveData<AuthState> state = new MutableLiveData<>();
    private final MutableLiveData<SignInState> signIn = new MutableLiveData<>(SignInState.IDLE);

    /** Held as a field because the store's listeners are otherwise collectable. */
    private final TokenStore.Listener listener = state::postValue;

    public AuthViewModel(@NonNull Application application) {
        // Graph.authIo, not Graph.io: a sign-in waits on the network, and queued on the cache's
        // one thread it would stall every screen's reads for as long as that takes.
        this(application, () -> Graph.auth(application), Graph.tokens(application),
                Graph.authIo());
    }

    @VisibleForTesting
    AuthViewModel(@NonNull Application application, Supplier<AuthRepository> repository,
            TokenStore tokens, Executor io) {
        super(application);
        this.repository = repository;
        this.tokens = tokens;
        this.io = io;
        tokens.addListener(listener);
        state.setValue(tokens.state());
    }

    public LiveData<AuthState> state() {
        return state;
    }

    public LiveData<SignInState> signInState() {
        return signIn;
    }

    /**
     * What a failed sign-in should tell the person who typed the passphrase.
     *
     * <p>Takes the kind of failure and the status rather than the exception so it can be tested
     * from outside {@code data.remote}, whose {@code ApiException} constructor is private — the
     * same arrangement {@code GenerateViewModel.messageFor} settled on. {@code status} is read
     * only for {@link Failure#ANSWERED}; anything else never got an answer worth reading.
     *
     * <p>{@link Failure#SLOW} is the reason this takes a kind at all. A Render cold start takes
     * about two minutes, longer than this client waits, and it used to reach this screen as
     * "Signing in needs a connection" on a device that was online the whole time.
     *
     * <p>{@code 503} is named because it is the answer that would otherwise be the most
     * misleading: it means the server has no passphrase configured, so nothing the person types
     * will ever work and telling them to check what they typed sends them round a loop with no
     * exit. Only the backend's own {@code 503} arrives here — a bodiless one is the router, and
     * {@link Failure#of} has already called it slow. {@code 429} is named for the inverse
     * reason: nothing is wrong, and the wait is minutes rather than forever.
     */
    @StringRes
    static int messageFor(Failure failure, int status) {
        return switch (failure) {
            case SLOW -> R.string.auth_error_slow;
            case OFFLINE -> R.string.auth_error_offline;
            case BROKEN -> R.string.auth_error_broken;
            case ANSWERED -> switch (status) {
                case 400, 401 -> R.string.auth_error_refused;
                case 429 -> R.string.auth_error_too_many;
                case 503 -> R.string.auth_error_unavailable;
                default -> R.string.auth_error_server;
            };
        };
    }

    public void signIn(@Nullable String passphrase) {
        if (passphrase == null || passphrase.isEmpty()) {
            signIn.setValue(new SignInState(false, false, R.string.auth_error_empty));
            return;
        }
        signIn.setValue(new SignInState(true, false, null));
        io.execute(() -> {
            try {
                repository.get().signIn(passphrase);
                signIn.postValue(new SignInState(false, true, null));
            } catch (ApiException e) {
                signIn.postValue(new SignInState(false, false,
                        messageFor(Failure.of(e), e.status())));
            } catch (IOException e) {
                signIn.postValue(new SignInState(false, false, messageFor(Failure.of(e), 0)));
            }
            // The store's listener publishes the new state on its own, so nothing here has to
            // remember to. That is the point of watching it rather than setting the state from
            // the two places that change it: a renewal in a background sync changes it too, and
            // that path has no view model in it at all.
        });
    }

    /** Puts the screen back to a blank attempt, so a message does not outlive the try it named. */
    public void clearSignInState() {
        signIn.setValue(SignInState.IDLE);
    }

    public void signOut() {
        io.execute(() -> repository.get().signOut());
    }

    @Override
    protected void onCleared() {
        tokens.removeListener(listener);
    }
}
