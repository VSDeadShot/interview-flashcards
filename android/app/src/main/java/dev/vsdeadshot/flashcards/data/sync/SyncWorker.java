package dev.vsdeadshot.flashcards.data.sync;

import android.content.Context;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import dev.vsdeadshot.flashcards.data.local.FlashcardsDatabase;
import dev.vsdeadshot.flashcards.data.remote.ApiClient;
import dev.vsdeadshot.flashcards.data.sync.SyncResult.Outcome;

/**
 * Runs one {@link SyncEngine#sync()} on WorkManager's own thread.
 *
 * <p>A {@link Worker} rather than a {@code ListenableWorker}: {@code sync()} blocks, and this is
 * already off the main thread, so there is nothing to make asynchronous.
 *
 * <p><strong>Only {@link Result#retry()} means anything different to a periodic request.</strong>
 * WorkManager routes success and failure through the same {@code resetPeriodic()} for periodic
 * work — the row goes back to {@code ENQUEUED} for the next interval either way, and neither
 * result's output data is stored. So the one question this method really answers is whether the
 * run wants to come back before the period is up. Failure is still returned where it is the
 * truthful answer, because the one-shot request does record it.
 */
public final class SyncWorker extends Worker {

    private static final String TAG = "SyncWorker";

    private final SyncEngine engine;

    public SyncWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        this(context, parameters,
                new SyncEngine(ApiClient.create(), FlashcardsDatabase.get(context)));
    }

    /** For tests, which supply an engine pointed at a loopback server and an in-memory cache. */
    SyncWorker(@NonNull Context context, @NonNull WorkerParameters parameters, SyncEngine engine) {
        super(context, parameters);
        this.engine = engine;
    }

    @NonNull
    @Override
    public Result doWork() {
        SyncResult result = engine.sync();
        Log.i(TAG, "Sync " + result.outcome()
                + ": pushed " + result.pushed()
                + ", dropped " + result.dropped()
                + ", stalled " + result.stalled()
                + ", wrote " + result.topicsWritten() + " topics and "
                + result.cardsWritten() + " cards");

        if (result.outcome() == Outcome.STOPPED) {
            // The key was rejected. A backoff timer cannot fix that, and retrying would spend
            // the battery repeating one 401 until something changes the key — which only a
            // person can do.
            return Result.failure();
        }
        // Anything left in the outbox, or a pull that did not finish, wants another attempt
        // sooner than the next period. The network constraint means that attempt waits for a
        // radio rather than burning a backoff slot without one.
        return result.hasWorkLeft() ? Result.retry() : Result.success();
    }
}
