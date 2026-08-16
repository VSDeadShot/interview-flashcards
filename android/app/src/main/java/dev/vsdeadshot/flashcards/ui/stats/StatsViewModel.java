package dev.vsdeadshot.flashcards.ui.stats;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.room.InvalidationTracker;
import dev.vsdeadshot.flashcards.data.StatsRepository;
import dev.vsdeadshot.flashcards.data.StatsRepository.StatsView;
import dev.vsdeadshot.flashcards.data.local.FlashcardsDatabase;
import dev.vsdeadshot.flashcards.ui.Graph;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * The seam between a screen and the cache, in the one shape every screen will use.
 *
 * <p>The repository stays blocking and composed — {@link StatsRepository#snapshot()} is five
 * queries in one transaction against an injected clock — and this class is what moves it off the
 * main thread. The alternative, having Room return a {@code LiveData} from a {@code @Query}, only
 * works for a read that <em>is</em> one query; it would mean pushing the composition up here and
 * losing both the transaction and a repository that can be tested synchronously.
 *
 * <p><strong>Freshness comes from Room's own invalidation tracker.</strong> A sync writing in the
 * background would otherwise leave the screen showing whatever it read when it opened. The
 * observer fires because {@code SyncWorker} writes through {@link FlashcardsDatabase#get} — the
 * same instance, in the same process, since nothing in the manifest asks for another one.
 */
public final class StatsViewModel extends AndroidViewModel {

    /**
     * Every table {@code snapshot()} reads. A table left out here is a figure that silently stops
     * updating, which is why this list is next to nothing else: it belongs to the read, not to
     * the screen.
     */
    private static final String[] TABLES = {"card", "topic", "review_tally", "stats_snapshot"};

    private final FlashcardsDatabase db;
    private final StatsRepository repository;
    private final Executor io;
    private final MutableLiveData<StatsView> stats = new MutableLiveData<>();
    private final InvalidationTracker.Observer observer;

    public StatsViewModel(@NonNull Application application) {
        this(application, Graph.database(application), Graph.stats(application), Graph.io());
    }

    @VisibleForTesting
    StatsViewModel(@NonNull Application application, FlashcardsDatabase db,
            StatsRepository repository, Executor io) {
        super(application);
        this.db = db;
        this.repository = repository;
        this.io = io;
        this.observer = new InvalidationTracker.Observer(TABLES) {
            @Override
            public void onInvalidated(@NonNull Set<String> tables) {
                reload();
            }
        };
        db.getInvalidationTracker().addObserver(observer);
        reload();
    }

    public LiveData<StatsView> stats() {
        return stats;
    }

    /**
     * Reads the cache on the background thread. {@code postValue} rather than {@code setValue}
     * because this never runs on the main thread — neither the executor nor the invalidation
     * callback is on it.
     */
    public void reload() {
        io.execute(() -> stats.postValue(repository.snapshot()));
    }

    @Override
    protected void onCleared() {
        // Room holds the observer strongly, so leaving it registered keeps this view model — and
        // the database read it schedules — alive for as long as the process is.
        db.getInvalidationTracker().removeObserver(observer);
    }
}
