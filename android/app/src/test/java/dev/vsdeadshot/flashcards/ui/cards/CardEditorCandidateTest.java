package dev.vsdeadshot.flashcards.ui.cards;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.view.View;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.textfield.TextInputEditText;
import dev.vsdeadshot.flashcards.R;
import dev.vsdeadshot.flashcards.data.local.CandidateEntity;
import dev.vsdeadshot.flashcards.ui.MainActivity;
import java.time.Instant;
import java.util.List;
import org.junit.Test;

/**
 * Correcting a generated card before it enters the deck.
 *
 * <p>Extends the card list's support class rather than the editor test's fixtures: this reaches
 * the editor the way a person does, by tapping a row on the card list, so the setup is that
 * screen's and only the reading of the editor's own fields is new here.
 *
 * <p>The case worth pinning is that saving writes what was typed rather than what the model
 * wrote, and that the candidate does not survive it. Getting that wrong leaves a row that comes
 * back for review after it has already been accepted, which nothing on screen would explain.
 */
public class CardEditorCandidateTest extends CardListTestSupport {

    @Test
    public void openingACandidateFillsTheEditorWithItsQuestionAndAnswer() throws Exception {
        cacheCandidate("What is 3NF?", "Third normal form.");

        MainActivity activity = openCandidateInEditor();

        assertEquals("the question should be prefilled",
                "What is 3NF?", text(activity, R.id.editor_front));
        assertEquals("the answer should be prefilled",
                "Third normal form.", text(activity, R.id.editor_back));
    }

    @Test
    public void savingAnEditedCandidateCreatesTheCardWithTheEditsNotTheOriginal() throws Exception {
        long id = cacheCandidate("What is 3NF?", "Third normal form.");

        MainActivity activity = openCandidateInEditor();
        setText(activity, R.id.editor_front, "What problem does 3NF solve?");
        activity.findViewById(R.id.editor_save).performClick();
        settle();

        assertEquals("exactly one card should have been written",
                1, db.cards().pendingCreates().size());
        assertEquals("the card should hold what the user typed, not what the model wrote",
                "What problem does 3NF solve?", db.cards().pendingCreates().get(0).front);
        assertEquals("the untouched side should survive as it was",
                "Third normal form.", db.cards().pendingCreates().get(0).back);
        assertNull("the candidate must not survive being accepted", db.candidates().find(id));
    }

    @Test
    public void theEditorIsTitledForAGeneratedCardRatherThanAnEdit() throws Exception {
        cacheCandidate("What is 3NF?", "Third normal form.");

        MainActivity activity = openCandidateInEditor();

        assertEquals("a generated card is neither new nor an edit of a saved card",
                "Add generated card",
                activity.getSupportActionBar().getTitle().toString());
    }

    /** There is nothing to archive: the candidate is not in the deck and the card does not exist. */
    @Test
    public void aCandidateOffersNoArchiveButton() throws Exception {
        cacheCandidate("What is 3NF?", "Third normal form.");

        MainActivity activity = openCandidateInEditor();

        assertEquals(View.GONE, activity.findViewById(R.id.editor_archive).getVisibility());
    }

    /** Leaving without saving keeps the candidate, so a change of mind costs nothing. */
    @Test
    public void openingACandidateWithoutSavingLeavesItInTheBand() throws Exception {
        long id = cacheCandidate("What is 3NF?", "Third normal form.");

        openCandidateInEditor();

        assertEquals("looking is not accepting", 1, db.candidates().count());
        assertEquals("and it should be the same row", "What is 3NF?",
                db.candidates().find(id).front);
        assertEquals("nothing should have been written to the deck",
                0, db.cards().pendingCreates().size());
    }

    // ---- fixtures -----------------------------------------------------------------------------

    /** Taps the first candidate row, which is the way a person reaches this screen. */
    private MainActivity openCandidateInEditor() throws InterruptedException {
        MainActivity activity = openActivity();
        RecyclerView list = activity.findViewById(R.id.cards_list);
        relayout(list);
        // Position 0 is the band's header; the first candidate sits directly under it.
        row(list, 1).performClick();
        settle();
        return activity;
    }

    private long cacheCandidate(String front, String back) {
        CandidateEntity candidate = new CandidateEntity();
        candidate.topicId = 1L;
        candidate.front = front;
        candidate.back = back;
        candidate.generatedAt = Instant.parse("2026-08-18T10:00:00Z");
        db.candidates().insertAll(List.of(candidate));
        return db.candidates().all().get(0).id;
    }

    private static String text(MainActivity activity, int id) {
        return ((TextView) activity.findViewById(id)).getText().toString();
    }

    private static void setText(MainActivity activity, int id, String value) {
        ((TextInputEditText) activity.findViewById(id)).setText(value);
    }
}
