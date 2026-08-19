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

    public void generate(long topicId, @Nullable String focus, int count) {
        state.setValue(new GenerateState(true, null, null));
        io.execute(() -> {
            try {
                // The repository is built here rather than held as a field: constructing it
                // constructs the API client, and ApiKeyInterceptor refuses a missing key at
                // construction. A view model that did it eagerly would take the whole screen
                // down on a build with no key rather than the one action that needs one.
                int stored = Graph.generator(getApplication()).generate(topicId, focus, count);
                state.postValue(new GenerateState(false, stored, null));
            } catch (ApiException e) {
                // 422 and 503 are separated on purpose: one invites a retry, the other says an
                // identical request will produce the same nothing.
                state.postValue(new GenerateState(false, null,
                        e.status() == 422 ? R.string.generate_error_refused
                                : R.string.generate_error_busy));
            } catch (IOException e) {
                // The one feature in this app that a dead radio actually stops. Everything else
                // was built so the network being absent changes nothing.
                state.postValue(new GenerateState(false, null, R.string.generate_error_offline));
            }
        });
    }
}
