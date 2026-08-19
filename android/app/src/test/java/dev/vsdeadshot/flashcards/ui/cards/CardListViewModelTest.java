package dev.vsdeadshot.flashcards.ui.cards;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.os.Looper;
import androidx.lifecycle.Observer;
import androidx.room.Room;
import dev.vsdeadshot.flashcards.data.CandidateRepository;
import dev.vsdeadshot.flashcards.data.CardRepository;
import dev.vsdeadshot.flashcards.data.local.CardSummaryRow;
import dev.vsdeadshot.flashcards.data.local.FlashcardsDatabase;
import dev.vsdeadshot.flashcards.data.local.TopicEntity;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The listing, kept current.
 *
 * <p>This screen subscribes to Room's invalidation tracker, unlike the study queue: nothing here
 * is mid-interaction, so a list refreshing under somebody is what they want rather than something
 * to protect them from. The case that matters is a card the server has just refused turning up as
 * rejected without anyone going looking for it.
 */
@RunWith(RobolectricTestRunner.class)
// FlashcardsApp is kept out of this: Robolectric does not create the app's content providers, so
// androidx.startup never initialises WorkManager and onCreate's call to it throws.
@Config(application = Application.class)
public class CardListViewModelTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 17);
    private static final Clock FIXED =
            Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    /** Runs the read on the calling thread: the hop is what is stood in for, not the thread. */
    private static final Executor DIRECT = Runnable::run;

    private FlashcardsDatabase db;
    private CardRepository repository;
    private CardListViewModel model;
    private Recorder recorder;

    @Before
    public void setUp() {
        db = Room.inMemoryDatabaseBuilder(
                        RuntimeEnvironment.getApplication(), FlashcardsDatabase.class)
                .allowMainThreadQueries()
                // Room refreshes its invalidation tracker on the query executor; running that
                // inline is what lets a write and the notification it causes be observed within
                // one test method rather than raced against.
                .setQueryExecutor(DIRECT)
                .build();
        repository = new CardRepository(db, FIXED);
        cacheTopic();
    }

    @After
    public void tearDown() {
        if (model != null) {
            model.onCleared();
        }
        db.close();
    }

    @Test
    public void whatIsAlreadyCachedIsListedWhenTheScreenOpens() {
        repository.create(1L, "written before the screen opened", "back");

        openScreen();

        assertEquals(1, shown().size());
        assertEquals("written before the screen opened", shown().get(0).front);
    }

    /**
     * The reason this screen subscribes at all. A sync running in the background is what parks a
     * card, and nobody would think to reopen the list to find out that it had.
     */
    @Test
    public void aCardTheServerRefusesTurnsUpAsRejectedOnItsOwn() {
        long cardId = repository.create(1L, "written here", "back").id;
        openScreen();
        assertFalse("nothing has refused it yet", shown().get(0).rejected());

        db.cards().recordSyncFailure(cardId, "No topic with id 42");

        assertTrue("the list follows the cache, and the cache had changed",
                shown().get(0).rejected());
        assertEquals("the server's own words, carried as far as the list",
                "No topic with id 42", shown().get(0).syncError);
    }

    @Test
    public void aCardWrittenAfterTheScreenOpenedAppearsWithoutAsking() {
        openScreen();
        assertTrue(shown().isEmpty());

        repository.create(1L, "written after", "back");

        assertEquals(1, shown().size());
    }

    /**
     * {@code topic} is in the observed set as well as {@code card}, because a row shows the
     * topic's name — so a pull that renames one has to redraw a list in which no card changed.
     */
    @Test
    public void renamingATopicRedrawsTheListEvenThoughNoCardChanged() {
        repository.create(1L, "front", "back");
        openScreen();
        assertEquals("Operating Systems", shown().get(0).topicName);

        cacheTopicNamed("Operating Systems and Concurrency");

        assertEquals("Operating Systems and Concurrency", shown().get(0).topicName);
    }

    @Test
    public void clearingTheViewModelStopsItListening() {
        openScreen();

        model.onCleared();
        repository.create(1L, "written after the screen went away", "back");

        assertTrue("a cleared view model has no screen left to tell", shown().isEmpty());
    }

    // ---- fixtures -----------------------------------------------------------------------------

    private void openScreen() {
        model = new CardListViewModel(RuntimeEnvironment.getApplication(), db, repository,
                new CandidateRepository(db), DIRECT);
        // The constructor's own load has already been posted; idling first is what makes the
        // observer's first value the current one rather than an empty starting point.
        idle();
        recorder = new Recorder();
        model.items().observeForever(recorder);
        idle();
    }

    /**
     * What the screen is showing.
     *
     * <p>Idles first, because {@code postValue} hands the value to the main looper and Robolectric
     * does not run it unasked — so reading the field straight after a write would see the list as
     * it was before.
     */
    private List<CardSummaryRow> shown() {
        idle();
        // The view model publishes one flattened list so the adapter can diff it; this test is
        // about the deck, and every case here runs with no generated batch, so unwrapping the
        // card rows keeps the assertions saying what they said before the band existed.
        List<CardSummaryRow> cards = new ArrayList<>();
        for (CardListItem item : recorder.value) {
            if (item instanceof CardListItem.Card card) {
                cards.add(card.card());
            }
        }
        return cards;
    }

    private void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static final class Recorder implements Observer<List<CardListItem>> {
        private List<CardListItem> value = List.of();

        @Override
        public void onChanged(List<CardListItem> updated) {
            value = updated;
        }
    }

    private void cacheTopic() {
        cacheTopicNamed("Operating Systems");
    }

    private void cacheTopicNamed(String name) {
        TopicEntity topic = new TopicEntity();
        topic.id = 1L;
        topic.name = name;
        topic.slug = "operating-systems";
        topic.createdAt = Instant.parse("2026-01-01T00:00:00Z");
        db.topics().upsertAll(List.of(topic));
    }
}
