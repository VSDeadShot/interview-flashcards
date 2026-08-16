package dev.vsdeadshot.flashcards.ui;

import android.content.Context;
import androidx.annotation.VisibleForTesting;
import dev.vsdeadshot.flashcards.data.ReviewRepository;
import dev.vsdeadshot.flashcards.data.StatsRepository;
import dev.vsdeadshot.flashcards.data.StudyRepository;
import dev.vsdeadshot.flashcards.data.local.FlashcardsDatabase;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Where a screen gets a repository and a thread to run it on.
 *
 * <p>A static holder rather than a dependency-injection framework. There are four screens and
 * three repositories, all of which are stateless wrappers over one database; a container that
 * had to be configured before any of them could be built would be more machinery than the thing
 * it assembles.
 *
 * <p><strong>The executor is single-threaded on purpose.</strong> A repository call may write —
 * recording a review, archiving a card — and reads of the same data are queued behind it rather
 * than racing it, so what a screen shows after an action is the state that action produced. A
 * pool would make that ordering a matter of timing.
 */
public final class Graph {

    private static volatile ExecutorService io;

    /** Non-null only in tests; see {@link #installDatabase}. */
    private static volatile FlashcardsDatabase database;

    private Graph() {
    }

    /** The one background thread every screen's reads and writes run on. */
    public static Executor io() {
        if (io == null) {
            synchronized (Graph.class) {
                if (io == null) {
                    io = Executors.newSingleThreadExecutor(runnable -> {
                        Thread thread = new Thread(runnable, "flashcards-io");
                        // Daemon, so a queued read cannot keep the process alive after the last
                        // screen has gone. Nothing here is a write the outbox depends on; those
                        // are already committed by the time this thread is idle.
                        thread.setDaemon(true);
                        return thread;
                    });
                }
            }
        }
        return io;
    }

    public static FlashcardsDatabase database(Context context) {
        FlashcardsDatabase installed = database;
        return installed != null ? installed : FlashcardsDatabase.get(context);
    }

    /**
     * Points every screen at a database of the caller's choosing.
     *
     * <p>Here so that a test exercising a real fragment does not have to go through
     * {@link FlashcardsDatabase#get}, whose instance is static and on disk and would outlive the
     * test that built it — leaving rows behind for whichever test class ran next. Production never
     * calls this, and {@link #reset()} puts it back.
     */
    @VisibleForTesting
    public static void installDatabase(FlashcardsDatabase db) {
        database = db;
    }

    @VisibleForTesting
    public static void reset() {
        database = null;
    }

    public static StatsRepository stats(Context context) {
        return new StatsRepository(database(context));
    }

    public static StudyRepository study(Context context) {
        return new StudyRepository(database(context));
    }

    public static ReviewRepository reviews(Context context) {
        return new ReviewRepository(database(context));
    }
}
