package dev.vsdeadshot.flashcards;

import android.app.Application;
import dev.vsdeadshot.flashcards.data.sync.SyncScheduler;

/**
 * Puts the periodic sync back on the schedule at every process start.
 *
 * <p>Here rather than in the first screen, because a process is often started by something other
 * than a person opening the app — including by WorkManager itself — and the schedule should not
 * depend on which. {@link SyncScheduler#ensureScheduled} keeps whatever is already enqueued, so
 * running on every start costs a lookup.
 */
public final class FlashcardsApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        SyncScheduler.ensureScheduled(this);
    }
}
