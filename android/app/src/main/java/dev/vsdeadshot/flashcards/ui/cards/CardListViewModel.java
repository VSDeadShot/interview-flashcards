package dev.vsdeadshot.flashcards.ui.cards;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.room.InvalidationTracker;
import dev.vsdeadshot.flashcards.data.CandidateRepository;
import dev.vsdeadshot.flashcards.data.CardRepository;
import dev.vsdeadshot.flashcards.data.local.CandidateEntity;
import dev.vsdeadshot.flashcards.data.local.CardSummaryRow;
import dev.vsdeadshot.flashcards.data.local.FlashcardsDatabase;
import dev.vsdeadshot.flashcards.ui.Graph;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Every card there is, and any generated batch still waiting on a decision, kept current.
 *
 * <p><strong>This screen does subscribe to Room's invalidation tracker</strong>, unlike the study
 * queue. Nothing here is mid-interaction — a list refreshing under somebody is what they want —
 * and it is how a card the server has just refused shows up as rejected without anyone going
 * looking for it. Accepting and discarding ride the same path rather than a second mechanism.
 */
public final class CardListViewModel extends AndroidViewModel {

    /**
     * The tables the listing reads. {@code topic} is in here because a row shows the topic's name,
     * so a pull that renames one has to redraw the list even though no card changed.
     */
    private static final String[] TABLES = {"card", "topic", "candidate"};

    private final FlashcardsDatabase db;
    private final CardRepository cards;
    private final CandidateRepository candidates;
    private final Executor io;
    private final MutableLiveData<List<CardListItem>> items = new MutableLiveData<>();
    private final InvalidationTracker.Observer observer;

    public CardListViewModel(@NonNull Application application) {
        this(application, Graph.database(application), Graph.cards(application),
                Graph.candidates(application), Graph.io());
    }

    @VisibleForTesting
    CardListViewModel(@NonNull Application application, FlashcardsDatabase db,
            CardRepository cards, CandidateRepository candidates, Executor io) {
        super(application);
        this.db = db;
        this.cards = cards;
        this.candidates = candidates;
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

    /** One flattened list, so the adapter can diff it. See {@link CardListItem}. */
    public LiveData<List<CardListItem>> items() {
        return items;
    }

    public void reload() {
        io.execute(() -> {
            List<CandidateEntity> batch = candidates.all();
            List<CardSummaryRow> deck = cards.list();

            List<CardListItem> rows = new ArrayList<>(batch.size() + deck.size() + 1);
            // The header is absent rather than empty when there is no batch, so the screen looks
            // exactly as it did before this feature until the moment one exists.
            if (!batch.isEmpty()) {
                rows.add(new CardListItem.Header(batch.size()));
                for (CandidateEntity candidate : batch) {
                    rows.add(new CardListItem.Candidate(candidate));
                }
            }
            for (CardSummaryRow card : deck) {
                rows.add(new CardListItem.Card(card));
            }
            items.postValue(rows);
        });
    }

    /**
     * Turns a candidate into an ordinary unsent card. No reload follows it: the write invalidates
     * both tables, and the observer above is what redraws the list.
     */
    public void accept(long candidateId) {
        io.execute(() -> candidates.accept(candidateId));
    }

    public void discard(long candidateId) {
        io.execute(() -> candidates.discard(candidateId));
    }

    public void discardAll() {
        io.execute(candidates::discardAll);
    }

    @Override
    protected void onCleared() {
        // Room holds the observer strongly, so leaving it registered keeps this view model and
        // the read it schedules alive for as long as the process is.
        db.getInvalidationTracker().removeObserver(observer);
    }
}
