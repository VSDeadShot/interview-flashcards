package dev.vsdeadshot.flashcards.ui.cards;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import dev.vsdeadshot.flashcards.data.CandidateRepository;
import dev.vsdeadshot.flashcards.data.CardRepository;
import dev.vsdeadshot.flashcards.data.local.CandidateEntity;
import dev.vsdeadshot.flashcards.data.local.CardEntity;
import dev.vsdeadshot.flashcards.data.local.TopicEntity;
import dev.vsdeadshot.flashcards.data.sync.SyncScheduler;
import dev.vsdeadshot.flashcards.ui.Graph;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Writing one card: the fields to fill in, and what happened when they were saved.
 *
 * <p>Not subscribed to Room's invalidation tracker, for the study screen's reason rather than the
 * card list's: this is somewhere text is being typed, and a sync landing mid-sentence must not
 * replace it. What the editor holds is a draft, and only saving turns it into a row.
 */
public final class CardEditorViewModel extends AndroidViewModel {

    /** No card. Local ids run downwards from zero and server ids start at 1, so 0 is never one. */
    public static final long NEW_CARD = 0L;

    /** Not a candidate. Same convention as {@link #NEW_CARD}, and for the same reason. */
    public static final long NO_CANDIDATE = 0L;

    private final CardRepository repository;
    private final CandidateRepository candidates;
    private final Executor io;
    private final long cardId;
    private final long candidateId;
    private final MutableLiveData<EditorState> state = new MutableLiveData<>();
    private final MutableLiveData<Outcome> outcome = new MutableLiveData<>();

    public CardEditorViewModel(@NonNull Application application, long cardId, long candidateId) {
        this(application, Graph.cards(application), Graph.candidates(application), Graph.io(),
                cardId, candidateId);
    }

    /**
     * Two sources behind one state, which is the cost this design accepted rather than adding a
     * fourth destination. The fields, the topic picker, the blank-side rule and the way back are
     * the same whichever is being written; only where the text came from and where saving sends
     * it differ, and the fragment is deliberately not told which it is looking at.
     */
    @VisibleForTesting
    CardEditorViewModel(@NonNull Application application, CardRepository repository,
            CandidateRepository candidates, Executor io, long cardId, long candidateId) {
        super(application);
        this.repository = repository;
        this.candidates = candidates;
        this.io = io;
        this.cardId = cardId;
        this.candidateId = candidateId;
        load();
    }

    public LiveData<EditorState> state() {
        return state;
    }

    /**
     * What became of the last save or archive, or null once it has been acted on.
     *
     * <p>Consumed rather than merely observed, because acting on it navigates. A plain
     * {@code LiveData} redelivers its last value to a new observer, so a rotation after saving
     * would navigate a second time from a screen that had already left.
     */
    public LiveData<Outcome> outcome() {
        return outcome;
    }

    public void consumeOutcome() {
        outcome.setValue(null);
    }

    private void load() {
        io.execute(() -> {
            List<TopicEntity> topics = repository.topics();
            if (candidateId != NO_CANDIDATE) {
                CandidateEntity candidate = candidates.find(candidateId);
                state.postValue(new EditorState(null, candidate, topics, candidate == null));
                return;
            }
            CardEntity card = cardId == NEW_CARD ? null : repository.find(cardId);
            state.postValue(
                    new EditorState(card, null, topics, cardId != NEW_CARD && card == null));
        });
    }

    /**
     * Writes the card, and asks for a sync so it does not sit here waiting for the hour to turn.
     *
     * <p>Blank sides are refused by the repository as well as by the screen. The screen's check is
     * what puts the message beside the field somebody has to fix; this one is what makes the rule
     * true regardless of which screen is asking.
     */
    public void save(long topicId, String front, String back) {
        io.execute(() -> {
            try {
                if (candidateId != NO_CANDIDATE) {
                    // What the user typed, not what the model wrote, and the candidate goes in
                    // the same transaction so it cannot come back for review afterwards.
                    candidates.acceptEdited(candidateId, topicId, front, back);
                } else if (cardId == NEW_CARD) {
                    repository.create(topicId, front, back);
                } else {
                    repository.edit(cardId, topicId, front, back);
                }
            } catch (IllegalArgumentException refused) {
                outcome.postValue(new Outcome(Outcome.Kind.REFUSED, refused.getMessage()));
                return;
            }
            outcome.postValue(new Outcome(Outcome.Kind.SAVED, null));
            SyncScheduler.syncNow(getApplication());
        });
    }

    public void archive() {
        io.execute(() -> {
            try {
                repository.archive(cardId);
            } catch (IllegalArgumentException gone) {
                outcome.postValue(new Outcome(Outcome.Kind.REFUSED, gone.getMessage()));
                return;
            }
            outcome.postValue(new Outcome(Outcome.Kind.ARCHIVED, null));
            SyncScheduler.syncNow(getApplication());
        });
    }

    /**
     * What the editor has to work with.
     *
     * @param card the card being edited, or null when writing a new one or correcting a
     *     candidate
     * @param candidate the generated card being corrected, or null otherwise
     * @param topics what a card may be filed under — <strong>empty on a device that has never
     *     synced</strong>, which is not a state to hide, since every save would be refused
     * @param missing true when the card or candidate was asked for and is no longer there
     */
    public record EditorState(@Nullable CardEntity card, @Nullable CandidateEntity candidate,
            List<TopicEntity> topics, boolean missing) {

        /**
         * True for a candidate as well as a blank editor, which is what hides the archive
         * button: a candidate is not in the deck, and there is no card until saving writes one.
         */
        public boolean isNew() {
            return card == null && !missing;
        }

        /** Nothing can be written without a topic, so an editor with none must say so. */
        public boolean canSave() {
            return !missing && !topics.isEmpty();
        }

        /** The question to start from, or null when there is nothing to prefill. */
        @Nullable
        public String front() {
            if (card != null) {
                return card.front;
            }
            return candidate != null ? candidate.front : null;
        }

        @Nullable
        public String back() {
            if (card != null) {
                return card.back;
            }
            return candidate != null ? candidate.back : null;
        }

        /** The topic to preselect, or 0 when there is none - which no real topic ever has. */
        public long topicId() {
            if (card != null) {
                return card.topicId;
            }
            return candidate != null ? candidate.topicId : 0L;
        }
    }

    /** The result of a save or an archive, delivered once. */
    public record Outcome(Kind kind, @Nullable String message) {
        public enum Kind {
            SAVED,
            ARCHIVED,
            REFUSED
        }
    }
}
