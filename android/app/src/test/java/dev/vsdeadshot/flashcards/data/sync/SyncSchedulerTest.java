package dev.vsdeadshot.flashcards.data.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Configuration;
import androidx.work.ListenableWorker;
import androidx.work.NetworkType;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerFactory;
import androidx.work.WorkerParameters;
import androidx.work.testing.SynchronousExecutor;
import androidx.work.testing.WorkManagerTestInitHelper;
import java.util.List;
import java.util.concurrent.ExecutionException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * What ends up enqueued, read back out of a WorkManager that runs on the test thread.
 *
 * <p>The configuration installs a factory that hands back a worker doing nothing, so a request
 * whose constraints the test environment considers satisfied cannot start a real sync against a
 * server that is not there. These tests are about the requests, not about running them.
 */
@RunWith(RobolectricTestRunner.class)
// FlashcardsApp is kept out of this: Robolectric does not create the app's content
// providers, so androidx.startup never initialises WorkManager and onCreate's call to it
// throws before this test can install one of its own.
@Config(application = Application.class)
public class SyncSchedulerTest {

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        WorkManagerTestInitHelper.initializeTestWorkManager(
                context,
                new Configuration.Builder()
                        .setExecutor(new SynchronousExecutor())
                        .setWorkerFactory(new WorkerFactory() {
                            @Override
                            public ListenableWorker createWorker(
                                    @NonNull Context appContext,
                                    @NonNull String workerClassName,
                                    @NonNull WorkerParameters parameters) {
                                return new DoNothing(appContext, parameters);
                            }
                        })
                        .build());
    }

    @Test
    public void theScheduleIsPeriodicAndWaitsForANetwork() throws Exception {
        SyncScheduler.ensureScheduled(context);

        WorkInfo scheduled = only(SyncScheduler.PERIODIC_WORK);
        assertNotNull("The recurring sync should be a periodic request, not a one-shot",
                scheduled.getPeriodicityInfo());
        assertEquals("A sync with no network to use should wait rather than fail",
                NetworkType.CONNECTED, scheduled.getConstraints().getRequiredNetworkType());
    }

    @Test
    public void schedulingItAgainDoesNotScheduleItTwice() throws Exception {
        SyncScheduler.ensureScheduled(context);
        SyncScheduler.ensureScheduled(context);

        assertEquals("Every process start calls this, so it has to be a no-op after the first",
                1, workFor(SyncScheduler.PERIODIC_WORK).size());
    }

    @Test
    public void askingToSyncNowLeavesTheScheduleAlone() throws Exception {
        SyncScheduler.ensureScheduled(context);

        SyncScheduler.syncNow(context);

        // The reason the two use different unique names. Enqueued under one name, the one-shot
        // would not join the periodic request — it would replace it, and the recurring sync
        // would be gone until something restarted the process.
        WorkInfo scheduled = only(SyncScheduler.PERIODIC_WORK);
        assertNotNull("The periodic request should survive a one-shot being asked for",
                scheduled.getPeriodicityInfo());
        assertNull("The one-shot should be exactly that",
                only(SyncScheduler.IMMEDIATE_WORK).getPeriodicityInfo());
    }

    @Test
    public void askingToSyncNowTwiceSendsOneSync() throws Exception {
        SyncScheduler.syncNow(context);
        SyncScheduler.syncNow(context);

        assertEquals("A run already in flight will drain whatever was written before it read the"
                        + " outbox, so a second request would only re-send what is going out",
                1, workFor(SyncScheduler.IMMEDIATE_WORK).size());
    }

    @Test
    public void bothRequestsRunTheSyncWorker() throws Exception {
        SyncScheduler.ensureScheduled(context);
        SyncScheduler.syncNow(context);

        assertTrue("The periodic request should name the sync worker",
                only(SyncScheduler.PERIODIC_WORK).getTags()
                        .contains(SyncWorker.class.getName()));
        assertTrue("The one-shot should name the same worker",
                only(SyncScheduler.IMMEDIATE_WORK).getTags()
                        .contains(SyncWorker.class.getName()));
    }

    // ---- fixtures -------------------------------------------------------------------------

    private List<WorkInfo> workFor(String uniqueName) throws ExecutionException,
            InterruptedException {
        return WorkManager.getInstance(context).getWorkInfosForUniqueWork(uniqueName).get();
    }

    private WorkInfo only(String uniqueName) throws ExecutionException, InterruptedException {
        List<WorkInfo> work = workFor(uniqueName);
        assertEquals("Expected exactly one request enqueued as " + uniqueName, 1, work.size());
        return work.get(0);
    }

    /** Stands in for {@link SyncWorker} so nothing here reaches for a network. */
    public static final class DoNothing extends Worker {

        public DoNothing(@NonNull Context context, @NonNull WorkerParameters parameters) {
            super(context, parameters);
        }

        @NonNull
        @Override
        public Result doWork() {
            return Result.success();
        }
    }
}
