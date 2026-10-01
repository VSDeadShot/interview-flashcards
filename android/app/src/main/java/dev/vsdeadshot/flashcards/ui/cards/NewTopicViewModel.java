package dev.vsdeadshot.flashcards.ui.cards;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import dev.vsdeadshot.flashcards.R;
import dev.vsdeadshot.flashcards.data.remote.ApiException;
import dev.vsdeadshot.flashcards.data.remote.Failure;
import dev.vsdeadshot.flashcards.data.sync.SyncScheduler;
import dev.vsdeadshot.flashcards.ui.Graph;
import java.io.IOException;

/**
 * One request to add a topic, and what became of it.
 *
 * <p>Not subscribed to Room's invalidation tracker, for generate's reason: this shows the progress
 * of a single request somebody is waiting on, and a background write has nothing to say about it.
 * The topic it creates reaches the screens the other way round — written to the cache, and picked
 * up by the view models that do watch it.
 */
public final class NewTopicViewModel extends AndroidViewModel {

    /**
     * Exactly one of {@code created} and {@code error} is set once {@code running} is false.
     *
     * @param created the name of the topic the server created, as it stored it
     * @param error   a string resource, so the sheet stays the only place that words things
     */
    public record TopicState(boolean running, String created, @StringRes Integer error) {
    }

    private final MutableLiveData<TopicState> state = new MutableLiveData<>();

    public NewTopicViewModel(@NonNull Application application) {
        super(application);
    }

    public LiveData<TopicState> state() {
        return state;
    }

    /**
     * What a failed create should tell the person who asked for it.
     *
     * <p>Takes the kind of failure and the status rather than the exception, as
     * {@link GenerateViewModel#messageFor} does, so it can be tested from outside
     * {@code data.remote}. {@code status} is read only for {@link Failure#ANSWERED}.
     *
     * <p><strong>401 asks for a sign-in</strong>, which is the one place this departs from
     * generation's table: since tokens, a 401 means there is no session or it could not be
     * renewed, and the toolbar's sign-in is the whole remedy. Generation still reads it as a
     * misconfigured server, a reading left over from the shared key.
     *
     * <p><strong>409 is not a failure to retry.</strong> The topic exists — possibly because an
     * earlier attempt landed after this side stopped waiting for it, since topics carry no
     * idempotency key. Saying so is the honest answer either way.
     */
    @StringRes
    static int messageFor(Failure failure, int status) {
        return switch (failure) {
            case SLOW -> R.string.new_topic_error_slow;
            case OFFLINE -> R.string.new_topic_error_offline;
            case BROKEN -> R.string.new_topic_error_broken;
            case ANSWERED -> switch (status) {
                case 400 -> R.string.new_topic_error_invalid;
                case 401 -> R.string.new_topic_error_signed_out;
                case 409 -> R.string.new_topic_error_duplicate;
                default -> R.string.new_topic_error_misconfigured;
            };
        };
    }

    /**
     * Sends the name, on {@link Graph#remoteIo()} rather than {@link Graph#io()}: the request can
     * wait twenty seconds on a slow server, and every screen's reads queue on {@code io}.
     */
    public void create(String name) {
        String trimmed = name == null ? "" : name.strip();
        if (trimmed.isEmpty()) {
            // The sheet does not offer the button for this; refusing here as well means a stray
            // keyboard "done" cannot spend a request on a 400.
            return;
        }
        state.setValue(new TopicState(true, null, null));
        Graph.remoteIo().execute(() -> {
            try {
                String created = Graph.topicCreator(getApplication()).create(trimmed).name;
                state.postValue(new TopicState(false, created, null));
            } catch (ApiException e) {
                if (e.status() == 409) {
                    // The topic exists on the server, and this device may not have it — a create
                    // whose answer was lost, or one made elsewhere. A sync is what brings it here.
                    SyncScheduler.syncNow(getApplication());
                }
                state.postValue(new TopicState(false, null, messageFor(Failure.of(e), e.status())));
            } catch (IOException e) {
                state.postValue(new TopicState(false, null, messageFor(Failure.of(e), 0)));
            }
        });
    }
}
