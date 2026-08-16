package dev.vsdeadshot.flashcards.ui.cards;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.room.InvalidationTracker;
import dev.vsdeadshot.flashcards.data.CardRepository;
import dev.vsdeadshot.flashcards.data.local.CardSummaryRow;
import dev.vsdeadshot.flashcards.data.local.FlashcardsDatabase;
import dev.vsdeadshot.flashcards.ui.Graph;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Every card there is, kept current.
 *
 * <p><strong>This screen does subscribe to Room's invalidation tracker</strong>, unlike the study
 * queue. Nothing here is mid-interaction — a list refreshing under somebody is what they want —
 * and it is how a card the server has just refused shows up as rejected without anyone going
 * looking for it.
 */
public final class CardListViewModel extends AndroidViewModel {

    /**
     * The tables the listing reads. {@code topic} is in here because a row shows the topic's name,
     * so a pull that renames one has to redraw the list even though no card changed.
     */
    private static final String[] TABLES = {"card", "topic"};

    private final FlashcardsDatabase db;
    private final CardRepository repository;
    private final Executor io;
    private final MutableLiveData<List<CardSummaryRow>> cards = new MutableLiveData<>();
    private final InvalidationTracker.Observer observer;

    public CardListViewModel(@NonNull Application application) {
        this(application, Graph.database(application), Graph.cards(application), Graph.io());
    }

    @VisibleForTesting
    CardListViewModel(@NonNull Application application, FlashcardsDatabase db,
            CardRepository repository, Executor io) {
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

    public LiveData<List<CardSummaryRow>> cards() {
        return cards;
    }

    public void reload() {
        io.execute(() -> cards.postValue(repository.list()));
    }

    @Override
    protected void onCleared() {
        // Room holds the observer strongly, so leaving it registered keeps this view model and
        // the read it schedules alive for as long as the process is.
        db.getInvalidationTracker().removeObserver(observer);
    }
}
