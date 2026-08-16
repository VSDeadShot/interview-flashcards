package dev.vsdeadshot.flashcards.ui.stats;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.os.Looper;
import androidx.lifecycle.Observer;
import androidx.room.Room;
import dev.vsdeadshot.flashcards.data.CardRepository;
import dev.vsdeadshot.flashcards.data.StatsRepository;
import dev.vsdeadshot.flashcards.data.StatsRepository.StatsView;
import dev.vsdeadshot.flashcards.data.local.CardEntity;
import dev.vsdeadshot.flashcards.data.local.FlashcardsDatabase;
import dev.vsdeadshot.flashcards.data.local.TopicEntity;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
 * The threading seam, which every screen after this one inherits.
 *
 * <p>What is pinned here is not that a query returns the right number — the repository's own test
 * does that. It is that a screen reads the cache without touching it from the main thread, and
 * that a write made by something else entirely reaches the screen without anybody asking.
 */
@RunWith(RobolectricTestRunner.class)
// FlashcardsApp is kept out of this: Robolectric does not create the app's content providers, so
// androidx.startup never initialises WorkManager and onCreate's call to it throws. A data-layer
// test has no business running the app's startup wiring anyway.
@Config(application = Application.class)
public class StatsViewModelTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 17);
    private static final Clock FIXED =
            Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    /**
     * Runs the read on the calling thread. What this stands in for is the hop off the main
     * thread, not the thread itself, so the test stays deterministic.
     */
    private static final Executor DIRECT = Runnable::run;

    private FlashcardsDatabase db;
    private CardRepository cards;
    private StatsViewModel model;

    @Before
    public void setUp() {
        db = Room.inMemoryDatabaseBuilder(
                        RuntimeEnvironment.getApplication(), FlashcardsDatabase.class)
                .allowMainThreadQueries()
                // Room refreshes its invalidation tracker on the query executor. Running that
                // inline is what lets a write and the notification it causes be observed within
                // one test method rather than raced against.
                .setQueryExecutor(DIRECT)
                .build();
        cards = new CardRepository(db, FIXED);
        cacheTopic();
    }

    @After
    public void tearDown() {
        if (model != null) {
            model.onCleared();
        }
        db.close();
    }

    /** The screen opening: what is already in the cache is there before anything is asked for. */
    @Test
    public void whatIsAlreadyCachedIsReadWhenTheScreenOpens() {
        cacheCard(1L, TODAY);
        cacheCard(2L, TODAY.plusDays(4));

        Recorder recorder = openScreen();

        assertNotNull("a screen that opens to nothing at all has no way to say why",
                recorder.latest);
        assertEquals(2, recorder.latest.totalCards());
        assertEquals(1, recorder.latest.dueToday());
    }

    /**
     * The one that matters. A card written by anything sharing this database — the sync worker in
     * production, a repository call here — invalidates {@code card}, and what is on screen follows
     * without the screen polling or being told to reload.
     */
    @Test
    public void aWriteFromSomewhereElseReachesTheScreenOnItsOwn() {
        Recorder recorder = openScreen();
        assertEquals(0, recorder.latest.totalCards());

        cards.create(1L, "written after the screen opened", "back");
        idle();

        assertEquals("the screen reads the cache, and the cache had changed",
                1, recorder.latest.totalCards());
        assertEquals(1, recorder.latest.dueToday());
    }

    /**
     * Nothing stays subscribed for its own sake: an observer Room still holds keeps this view
     * model, and the database read it schedules, alive for as long as the process is.
     */
    @Test
    public void clearingTheViewModelStopsItListening() {
        Recorder recorder = openScreen();
        StatsView before = recorder.latest;

        model.onCleared();
        cards.create(1L, "written after the screen went away", "back");
        idle();

        assertEquals("a cleared view model has no screen left to tell", before, recorder.latest);
    }

    // ---- fixtures ---------------------------------------------------------------------------

    /** Builds the view model and subscribes to it, the way the fragment does. */
    private Recorder openScreen() {
        model = new StatsViewModel(RuntimeEnvironment.getApplication(), db,
                new StatsRepository(db, FIXED), DIRECT);
        // The constructor's own load has already been posted; idling first is what makes the
        // observer's first value the current one rather than an empty starting point.
        idle();
        Recorder recorder = new Recorder();
        model.stats().observeForever(recorder);
        idle();
        return recorder;
    }

    /**
     * {@code postValue} hands the value to the main looper, which Robolectric does not run until
     * it is told to.
     */
    private void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static final class Recorder implements Observer<StatsView> {
        private StatsView latest;

        @Override
        public void onChanged(StatsView value) {
            latest = value;
        }
    }

    private void cacheCard(long id, LocalDate dueDate) {
        CardEntity card = new CardEntity();
        card.id = id;
        card.serverId = id;
        card.topicId = 1L;
        card.front = "front " + id;
        card.back = "back " + id;
        card.easeFactor = 2.5d;
        card.intervalDays = 1;
        card.repetitions = 1;
        card.dueDate = dueDate;
        db.cards().upsertAll(List.of(card));
    }

    private void cacheTopic() {
        TopicEntity topic = new TopicEntity();
        topic.id = 1L;
        topic.name = "Operating Systems";
        topic.slug = "operating-systems";
        topic.createdAt = Instant.parse("2026-01-01T00:00:00Z");
        db.topics().upsertAll(List.of(topic));
    }
}
