package dev.vsdeadshot.flashcards.ui.cards;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.room.InvalidationTracker;
import dev.vsdeadshot.flashcards.data.CandidateRepository;
import dev.vsdeadshot.flashcards.data.CardRepository;
import dev.vsdeadshot.flashcards.data.local.CandidateEntity;
import dev.vsdeadshot.flashcards.data.local.CardEntity;
import dev.vsdeadshot.flashcards.data.local.CardSummaryRow;
import dev.vsdeadshot.flashcards.data.local.FlashcardsDatabase;
import dev.vsdeadshot.flashcards.data.local.TopicEntity;
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

    /**
     * No filter.
     *
     * <p>Zero is the topic id that means all of them, matching the nav argument and the
     * convention the editor already uses: local ids run downwards from zero and server ids start
     * at 1, so zero is the one value that is never a real id.
     */
    public static final long ALL_TOPICS = 0L;

    private final FlashcardsDatabase db;
    private final CardRepository cards;
    private final CandidateRepository candidates;
    private final Executor io;
    private final MutableLiveData<List<CardListItem>> items = new MutableLiveData<>();
    private final MutableLiveData<List<TopicEntity>> topics = new MutableLiveData<>();
    private final MutableLiveData<Long> justAdded = new MutableLiveData<>();
    private final InvalidationTracker.Observer observer;

    /**
     * Which topic the listing is scoped to, or {@link #ALL_TOPICS}.
     *
     * <p>A plain field rather than a LiveData. Nothing observes the filter itself — the chips are
     * the only thing that could, and they are what set it — so publishing it would be a second
     * value that has to stay in step with the list this already rebuilds.
     */
    private long filter = ALL_TOPICS;

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

    /**
     * One flattened list, so the adapter can diff it. See {@link CardListItem}.
     *
     * <p>Scoped by topic where the saved cards are concerned. <strong>The band is not
     * scoped.</strong> Candidates are not in the deck yet, and unresolved work must not be
     * hideable by a view control — a batch nobody has decided about would otherwise vanish by
     * switching chip, and be forgotten. The cost is that a batch generated for one topic stays on
     * screen while another is being looked at, which the header's own count makes legible.
     */
    public LiveData<List<CardListItem>> items() {
        return items;
    }

    /**
     * Every topic there is, for the chips.
     *
     * <p>Not only the ones currently holding a card. A chip that disappeared when its last card
     * was archived would look like the topic had been deleted, and nothing deletes topics.
     */
    public LiveData<List<TopicEntity>> topics() {
        return topics;
    }

    /**
     * The card an accepted candidate just became, delivered once.
     *
     * <p>Consumed rather than merely observed, because acting on it starts an animation. A plain
     * {@code LiveData} redelivers its last value to a new observer, so a rotation would replay
     * the highlight on a row that arrived minutes ago.
     */
    public LiveData<Long> justAdded() {
        return justAdded;
    }

    public void consumeJustAdded() {
        justAdded.setValue(null);
    }

    /** The topic currently selected, or {@link #ALL_TOPICS}. */
    public long filter() {
        return filter;
    }

    /** What to call the selected topic, or null when none is selected or none is cached. */
    @Nullable
    public String filterName() {
        List<TopicEntity> known = topics.getValue();
        if (known == null || filter == ALL_TOPICS) {
            return null;
        }
        for (TopicEntity topic : known) {
            if (topic.id == filter) {
                return topic.name;
            }
        }
        // Selected, then deleted on the server and dropped by a pull. The chip goes with it on
        // the next redraw; until then there is no name to put in a sentence.
        return null;
    }

    /** Scopes the listing to one topic, or back to all of them. */
    public void filterBy(long topicId) {
        if (topicId == filter) {
            return;
        }
        filter = topicId;
        reload();
    }

    public void reload() {
        io.execute(() -> {
            List<CandidateEntity> batch = candidates.all();
            List<CardSummaryRow> deck = cards.list();
            topics.postValue(cards.topics());

            List<CardListItem> rows = new ArrayList<>(batch.size() + deck.size() + 1);
            // The header is absent rather than empty when there is no batch, so the screen looks
            // exactly as it did before this feature until the moment one exists.
            if (!batch.isEmpty()) {
                rows.add(new CardListItem.Header(batch.size()));
                for (CandidateEntity candidate : batch) {
                    rows.add(new CardListItem.Candidate(candidate));
                }
            }
            // Scoped in the loop rather than by a second query. The listing is one query
            // returning every card, which is already one moment, and a WHERE clause would make
            // each chip a database round trip while buying nothing on a deck this size.
            //
            // Matched on topicId and never on topicName. The name is null whenever the cache
            // does not hold the topic, and those are exactly the cards the left join exists to
            // keep visible — filtering by name would drop them from every chip including All.
            for (CardSummaryRow card : deck) {
                if (filter == ALL_TOPICS || card.topicId == filter) {
                    rows.add(new CardListItem.Card(card));
                }
            }
            items.postValue(rows);
        });
    }

    /**
     * Turns a candidate into an ordinary unsent card. No reload follows it: the write invalidates
     * both tables, and the observer above is what redraws the list.
     */
    public void accept(long candidateId) {
        io.execute(() -> {
            CardEntity created = candidates.accept(candidateId);
            if (created != null) {
                // Named so the row it became can be pointed at for a second. Nothing else on the
                // screen says which of however many rows is the new one, and the list has just
                // grown by exactly the row nobody can find.
                justAdded.postValue(created.id);
            }
        });
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
