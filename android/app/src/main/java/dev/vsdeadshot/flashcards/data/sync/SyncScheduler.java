package dev.vsdeadshot.flashcards.data.sync;

import android.content.Context;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import java.time.Duration;

/**
 * When {@link SyncWorker} runs.
 *
 * <p>Two requests under two names, which is a decision rather than an oversight.
 * {@code enqueueUniqueWork} and {@code enqueueUniquePeriodicWork} share one table of names, so a
 * one-shot enqueued under the periodic request's name does not join it — it replaces it, and the
 * schedule is gone until the next process start puts it back.
 *
 * <p>What two names give up is a guarantee that the two can never overlap: a period elapsing
 * while a one-shot is mid-flight runs both. That is survivable, and only here. Every review
 * carries a {@code clientReviewId}, so the second run's send is answered with the first run's
 * outcome instead of applying SM-2 twice, and the pull decides which cards it may touch inside
 * its own transaction. The tally each run reports is wrong in that case; nothing else is.
 */
public final class SyncScheduler {

    // Public because they name work, and anything that enqueues or asks after a run needs to
    // name the same thing. A caller that spelled the string itself would keep working right up
    // until one of the two spellings changed.
    public static final String PERIODIC_WORK = "sync-periodic";
    public static final String IMMEDIATE_WORK = "sync-now";

    /**
     * Often enough that a phone that spent the morning offline is current by lunch, rarely
     * enough not to be the reason a battery report names this app. WorkManager will not go
     * below 15 minutes anyway.
     */
    private static final Duration PERIOD = Duration.ofHours(1);

    private static final Duration BACKOFF = Duration.ofSeconds(30);

    private SyncScheduler() {
    }

    /**
     * The safety net, and idempotent: {@code KEEP} means a process that starts ten times a day
     * re-enqueues nothing and, in particular, does not reset the interval each time.
     */
    public static void ensureScheduled(Context context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                new PeriodicWorkRequest.Builder(SyncWorker.class, PERIOD)
                        .setConstraints(whenConnected())
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF)
                        .build());
    }

    /**
     * Send what was just recorded, now. {@code KEEP} rather than {@code REPLACE} because a run
     * already in flight will drain whatever was written before it read the outbox, and cancelling
     * it to start again would only re-send what it is already sending.
     *
     * <p>This is also what handles a review answered with the radio off: the request is enqueued
     * with a network constraint and WorkManager holds it until there is one. There is no separate
     * connectivity listener, because that is the same thing spelled longer.
     */
    public static void syncNow(Context context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_WORK,
                ExistingWorkPolicy.KEEP,
                new OneTimeWorkRequest.Builder(SyncWorker.class)
                        .setConstraints(whenConnected())
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF)
                        .build());
    }

    private static Constraints whenConnected() {
        return new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
    }
}
