package dev.vsdeadshot.flashcards.ui.cards;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import dev.vsdeadshot.flashcards.R;
import dev.vsdeadshot.flashcards.data.local.TopicEntity;
import dev.vsdeadshot.flashcards.data.remote.ApiException;
import dev.vsdeadshot.flashcards.data.remote.Failure;
import dev.vsdeadshot.flashcards.ui.Graph;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * One request, and what became of it.
 *
 * <p><strong>Deliberately not subscribed to Room's invalidation tracker</strong>, unlike the
 * stats and card-list view models. This shows the progress of a single request somebody is
 * sitting and waiting on; a background write has nothing to say about that, and redrawing the
 * sheet under them mid-typing is the one thing it could do.
 */
public final class GenerateViewModel extends AndroidViewModel {

    /**
     * Exactly one of {@code generated} and {@code error} is set once {@code running} is false.
     *
     * <p>{@code error} is a string resource id rather than a message, so the view model never
     * builds user-facing copy and the sheet stays the only place that knows how to say things.
     */
    public record GenerateState(boolean running, Integer generated, @StringRes Integer error) {
    }

    private final MutableLiveData<List<TopicEntity>> topics = new MutableLiveData<>();
    private final MutableLiveData<GenerateState> state = new MutableLiveData<>();
    private final Executor io;

    public GenerateViewModel(@NonNull Application application) {
        super(application);
        this.io = Graph.io();
        io.execute(() -> topics.postValue(Graph.cards(getApplication()).topics()));
    }

    /** The topics a batch can be asked for, from the cache like everything else on screen. */
    public LiveData<List<TopicEntity>> topics() {
        return topics;
    }

    public LiveData<GenerateState> state() {
        return state;
    }

    /**
     * What a failed generation should tell the person who asked for it.
     *
     * <p>Takes the kind of failure and the status rather than the exception so it can be tested
     * from outside {@code data.remote}, whose {@code ApiException} constructor is private.
     * {@code status} is read only for {@link Failure#ANSWERED}.
     *
     * <p><strong>Among the backend's own answers, only 503 invites a retry now, and 429 invites
     * one tomorrow.</strong> The backend answers 503 for an upstream that did not respond, 422
     * for a model that had nothing usable to say, 429 when the day's generation allowance is
     * spent, and a bodyless 500 for our own credential or model name being wrong -- which
     * {@code ApiExceptionHandler} leaves unmapped on purpose, so nothing about the
     * misconfiguration is described to a caller. Defaulting the unrecognised case to "busy, try
     * again shortly" made this side repeat the mistake that was fixed one layer down: a request
     * the server has rejected as ours is not a passing outage, and telling somebody to wait for a
     * key to start working asks them to wait forever.
     *
     * <p>429 has to be named here for that same reason inverted. Left to the default it would be
     * reported as a server that is set up wrongly and will never work, when in fact nothing is
     * wrong and it works again at midnight. 404 is named because it is a topic this device
     * holds and the server does not, which a sync fixes and "not set up correctly" would have
     * sent somebody looking for a server fault instead.
     *
     * <p>A server that was slow to answer, or whose router answered for it, is
     * {@link Failure#SLOW} and never reaches the status switch — so a gateway's 502 cannot be
     * mistaken for the misconfigured default.
     */
    @StringRes
    static int messageFor(Failure failure, int status) {
        return switch (failure) {
            case SLOW -> R.string.generate_error_slow;
            case OFFLINE -> R.string.generate_error_offline;
            case BROKEN -> R.string.generate_error_broken;
            case ANSWERED -> switch (status) {
                case 404 -> R.string.generate_error_missing_topic;
                case 422 -> R.string.generate_error_refused;
                case 429 -> R.string.generate_error_limit;
                case 503 -> R.string.generate_error_busy;
                default -> R.string.generate_error_misconfigured;
            };
        };
    }

    public void generate(long topicId, @Nullable String focus, int count) {
        state.setValue(new GenerateState(true, null, null));
        io.execute(() -> {
            try {
                // The repository is built here rather than held as a field. It began as a
                // workaround — constructing it constructed an API client, and the old
                // ApiKeyInterceptor refused a missing key at construction, so a view model that
                // did it eagerly would take the whole screen down on a build with no key. That
                // constraint went with the key. It stays because building a client per action
                // costs nothing here and keeps this screen's one networked thing self-contained.
                int stored = Graph.generator(getApplication()).generate(topicId, focus, count);
                state.postValue(new GenerateState(false, stored, null));
            } catch (ApiException e) {
                state.postValue(new GenerateState(false, null,
                        messageFor(Failure.of(e), e.status())));
            } catch (IOException e) {
                // One of the two features in this app that a dead radio actually stops, adding a
                // topic being the other. Everything else was built so the network being absent
                // changes nothing — though not every IOException is a dead radio, which is what
                // Failure.of sorts out.
                state.postValue(new GenerateState(false, null, messageFor(Failure.of(e), 0)));
            }
        });
    }
}
