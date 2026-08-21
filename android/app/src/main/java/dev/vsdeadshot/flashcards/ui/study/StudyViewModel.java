package dev.vsdeadshot.flashcards.ui.study;

import android.app.Application;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.room.InvalidationTracker;
import dev.vsdeadshot.flashcards.data.ReviewRepository;
import dev.vsdeadshot.flashcards.data.StudyRepository;
import dev.vsdeadshot.flashcards.data.StudyRepository.StudyView;
import dev.vsdeadshot.flashcards.data.local.FlashcardsDatabase;
import dev.vsdeadshot.flashcards.data.sync.SyncScheduler;
import dev.vsdeadshot.flashcards.ui.Graph;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * The study loop: one card, revealed or not, and what happens when it is answered.
 *
 * <p><strong>This screen subscribes to Room's invalidation tracker, but acts on it only while
 * nothing is on screen.</strong> A sync landing mid-session must not replace the card somebody is
 * halfway through answering, possibly between the reveal and the button press — a number changing
 * under you is fine, the question changing under you is not. That is a reason to protect a card
 * that is showing, though, and not a reason to ignore the cache entirely: with an empty queue
 * there is no answer in progress to disturb, and refusing to reload there left the caught-up
 * screen telling somebody to run a sync which then appeared to do nothing, because the cards it
 * fetched could not reach the screen until the tab was left and reopened.
 *
 * <p>The traffic in the other direction still works and costs nothing: answering a card writes to
 * {@code card}, which invalidates the stats screen's observer, so the due count on that tab
 * follows on its own.
 */
public final class StudyViewModel extends AndroidViewModel {

    private static final String TAG = "StudyViewModel";

    /**
     * Every table {@link StudyRepository#next()} reads. {@code topic} is in here for the heading
     * beside the question, which arrives in the same pull as the cards and would otherwise be
     * missing from a queue that appeared without one.
     */
    private static final String[] TABLES = {"card", "topic"};

    private final FlashcardsDatabase db;
    private final StudyRepository study;
    private final ReviewRepository reviews;
    private final Executor io;
    private final Executor main;
    private final MutableLiveData<StudyState> state = new MutableLiveData<>();
    private final InvalidationTracker.Observer observer;

    public StudyViewModel(@NonNull Application application) {
        this(application, Graph.database(application), Graph.study(application),
                Graph.reviews(application), Graph.io());
    }

    @VisibleForTesting
    StudyViewModel(@NonNull Application application, FlashcardsDatabase db, StudyRepository study,
            ReviewRepository reviews, Executor io) {
        super(application);
        this.db = db;
        this.study = study;
        this.reviews = reviews;
        this.io = io;
        this.main = ContextCompat.getMainExecutor(application);
        this.observer = new InvalidationTracker.Observer(TABLES) {
            @Override
            public void onInvalidated(@NonNull Set<String> tables) {
                reloadIfNothingShowing();
            }
        };
        db.getInvalidationTracker().addObserver(observer);
        reload();
    }

    public LiveData<StudyState> state() {
        return state;
    }

    /**
     * Reads the queue again, keeping the answer on screen if it is still the same card.
     *
     * <p>That condition is what lets this be called from {@code onResume} without undoing a
     * reveal: a rotation resumes the screen with the card unchanged, so the answer stays up,
     * while coming back to a screen whose card was archived elsewhere starts the new one face
     * down. Called on the main thread — it reads what is currently showing before hopping off it.
     */
    public void reload() {
        StudyState showing = state.getValue();
        Long showingId = showing != null && showing.view().hasCard()
                ? showing.view().card().id
                : null;
        boolean wasRevealed = showing != null && showing.revealed();

        io.execute(() -> {
            StudyView next = study.next();
            boolean sameCard = showingId != null && next.hasCard()
                    && next.card().id == showingId.longValue();
            state.postValue(new StudyState(next, sameCard && wasRevealed));
        });
    }

    /**
     * Reloads for a write this screen did not make, but only when there is nothing to interrupt.
     *
     * <p>The guard is read on the main thread rather than from the invalidation callback's own,
     * because {@code state} is the single record of what is showing and {@link #reload()} already
     * reads it there. A flag kept beside it would be a second answer to the same question, and
     * the two would eventually disagree.
     *
     * <p>Reloading into an empty queue that is still empty costs one read and posts an equal
     * state; it writes nothing, so it cannot invalidate anything and call itself back.
     */
    private void reloadIfNothingShowing() {
        main.execute(() -> {
            StudyState showing = state.getValue();
            if (showing != null && showing.view().hasCard()) {
                return;
            }
            reload();
        });
    }

    /** Turns the current card over. On the main thread, so this is the click straight through. */
    public void reveal() {
        StudyState showing = state.getValue();
        if (showing != null && showing.view().hasCard()) {
            state.setValue(new StudyState(showing.view(), true));
        }
    }

    /**
     * Records the answer, moves to the next card, and asks for a sync.
     *
     * <p>All three on {@link Graph#io()}, which is one thread handing out work in order — so the
     * read that produces the next card is queued behind the write that retired this one and
     * cannot see the queue as it was before. Nothing coordinates them; the executor does.
     */
    public void answer(int confidence) {
        StudyState showing = state.getValue();
        if (showing == null || !showing.view().hasCard()) {
            // Nothing on screen to answer. A double tap on the last card lands here.
            return;
        }
        long cardId = showing.view().card().id;

        io.execute(() -> {
            boolean recorded = true;
            try {
                reviews.record(cardId, confidence);
            } catch (IllegalArgumentException gone) {
                // Archived or deleted on another device between the read and the tap. Reloading
                // is the entire repair, and the answer is not worth reporting to somebody who
                // would only be told that a card they no longer have did not take their answer.
                Log.w(TAG, "Card " + cardId + " was gone by the time it was answered", gone);
                recorded = false;
            }
            // Face down, unconditionally: this is a different card, and the one thing worse than
            // no answer on screen is the previous card's answer under the next card's question.
            state.postValue(new StudyState(study.next(), false));
            if (recorded) {
                // Unique work under KEEP, so a run of quick answers coalesces into one pending
                // run rather than one request each.
                SyncScheduler.syncNow(getApplication());
            }
        });
    }

    @Override
    protected void onCleared() {
        // Room holds the observer strongly, so leaving it registered keeps this view model — and
        // the database read it schedules — alive for as long as the process is.
        db.getInvalidationTracker().removeObserver(observer);
    }

    /**
     * What the screen draws.
     *
     * <p>One value rather than a card and a separate revealed flag. Two would be posted
     * separately, and a new card arriving a frame before its flag went back to false would show
     * the next question with the last answer still under it.
     */
    public record StudyState(StudyView view, boolean revealed) {
    }
}
