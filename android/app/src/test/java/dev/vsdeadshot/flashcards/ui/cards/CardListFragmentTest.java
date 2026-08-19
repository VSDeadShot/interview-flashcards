package dev.vsdeadshot.flashcards.ui.cards;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.view.View;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.color.MaterialColors;
import dev.vsdeadshot.flashcards.R;
import dev.vsdeadshot.flashcards.data.CardRepository;
import dev.vsdeadshot.flashcards.data.local.CardEntity;
import dev.vsdeadshot.flashcards.ui.MainActivity;
import java.time.LocalDate;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

/**
 * The list as it is actually assembled — real fragment, real adapter, real {@code Graph}.
 *
 * <p>What this is for beyond assembly is the status label, which is the first time
 * {@code card.syncError} is put in front of a person. A row says at most one thing, and which one
 * it says is decided in the adapter rather than in the query, so nothing else can check it.
 */
public class CardListFragmentTest extends CardListTestSupport {

    private CardRepository repository;

    @Before
    public void openRepository() {
        repository = new CardRepository(db);
    }

    @Test
    public void everyCardIsListedWithItsTopic() throws Exception {
        cachePulledCard(1L, "What is a deadlock?");
        cachePulledCard(2L, "What is a page fault?");

        RecyclerView list = openCardsTab();

        assertEquals(2, list.getAdapter().getItemCount());
        assertEquals("What is a deadlock?", text(list, 0, R.id.card_front));
        assertEquals("Operating Systems", text(list, 0, R.id.card_topic));
    }

    @Test
    public void aSyncedCardSaysNothingAboutSyncing() throws Exception {
        cachePulledCard(1L, "front");

        RecyclerView list = openCardsTab();

        assertEquals("most rows are synced, and a badge on every one of them would be noise",
                View.GONE, visibility(list, 0, R.id.card_status));
    }

    @Test
    public void aCardWrittenHereIsMarkedUnsent() throws Exception {
        repository.create(1L, "written here", "back");

        RecyclerView list = openCardsTab();

        assertEquals(View.VISIBLE, visibility(list, 0, R.id.card_status));
        assertEquals("Unsent", text(list, 0, R.id.card_status));
    }

    /**
     * The one row worth acting on. It is also unsent, and saying only that would hide the fact
     * that nothing more will be tried until the card is edited.
     */
    @Test
    public void aCardTheServerRefusedIsMarkedRejectedAndInTheErrorColour() throws Exception {
        long cardId = repository.create(1L, "written here", "back").id;
        db.cards().recordSyncFailure(cardId, "No topic with id 42");

        RecyclerView list = openCardsTab();

        assertEquals("Rejected", text(list, 0, R.id.card_status));
        TextView status = (TextView) row(list, 0).findViewById(R.id.card_status);
        assertEquals(MaterialColors.getColor(status, androidx.appcompat.R.attr.colorError),
                status.getCurrentTextColor());
        assertNotEquals("rejected has to look different from merely unsent, not just read"
                        + " differently",
                MaterialColors.getColor(
                        status, com.google.android.material.R.attr.colorOnSurfaceVariant),
                status.getCurrentTextColor());
    }

    /**
     * A card outlives its topic being deleted on the server. A blank line where the topic goes
     * would read as a row that failed to load.
     */
    @Test
    public void aCardWithNoCachedTopicSaysSoRatherThanShowingABlank() throws Exception {
        cacheOrphanCard();

        RecyclerView list = openCardsTab();

        assertEquals("No topic", text(list, 0, R.id.card_topic));
    }

    @Test
    public void anEmptyCacheShowsTheMessageInsteadOfTheList() throws Exception {
        MainActivity activity = openActivity();

        assertEquals(View.VISIBLE, activity.findViewById(R.id.cards_empty).getVisibility());
        assertEquals("an empty message on top of a list about to arrive reads as a failure",
                View.GONE, activity.findViewById(R.id.cards_list).getVisibility());
    }

    private void cacheOrphanCard() {
        CardEntity orphan = new CardEntity();
        orphan.id = 9L;
        orphan.serverId = 9L;
        orphan.topicId = 404L;
        orphan.front = "topic went away";
        orphan.back = "back";
        orphan.dueDate = LocalDate.now();
        db.cards().upsertAll(List.of(orphan));
    }
}
