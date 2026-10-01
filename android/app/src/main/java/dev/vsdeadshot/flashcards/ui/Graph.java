package dev.vsdeadshot.flashcards.ui;

import android.content.Context;
import androidx.annotation.VisibleForTesting;
import dev.vsdeadshot.flashcards.data.AuthRepository;
import dev.vsdeadshot.flashcards.data.CandidateRepository;
import dev.vsdeadshot.flashcards.data.CardRepository;
import dev.vsdeadshot.flashcards.data.ReviewRepository;
import dev.vsdeadshot.flashcards.data.StatsRepository;
import dev.vsdeadshot.flashcards.data.StudyRepository;
import dev.vsdeadshot.flashcards.data.TopicRepository;
import dev.vsdeadshot.flashcards.data.auth.PrefsTokenStore;
import dev.vsdeadshot.flashcards.data.auth.TokenStore;
import dev.vsdeadshot.flashcards.data.local.FlashcardsDatabase;
import dev.vsdeadshot.flashcards.data.remote.ApiClient;
import dev.vsdeadshot.flashcards.data.remote.FlashcardsApi;
import java.time.Clock;
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
    private static volatile ExecutorService authIo;
    private static volatile ExecutorService remoteIo;

    /** Non-null only in tests; see {@link #installDatabase}. */
    private static volatile FlashcardsDatabase database;

    /** Non-null only in tests; see {@link #installApi}. */
    private static volatile FlashcardsApi api;

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

    /**
     * The thread signing in and out run on, and nothing else.
     *
     * <p>Not {@link #io()}, because a sign-in waits on the network for as long as a server waking
     * from idle takes, and every screen's reads queue on that one thread — so sharing it would
     * freeze the rest of the app for the whole wait. Nothing here needs ordering against the
     * cache: signing in and out write only the token store, never Room. It is single-threaded
     * for the same reason {@code io} is, so a sign-out tapped during a sign-in runs after it.
     */
    public static Executor authIo() {
        if (authIo == null) {
            synchronized (Graph.class) {
                if (authIo == null) {
                    authIo = Executors.newSingleThreadExecutor(runnable -> {
                        Thread thread = new Thread(runnable, "flashcards-auth");
                        // Daemon for io's reason: a sign-in still waiting must not keep the
                        // process alive after the last screen has gone. What it writes is one
                        // committed preference, so there is nothing half-done to lose.
                        thread.setDaemon(true);
                        return thread;
                    });
                }
            }
        }
        return authIo;
    }

    /**
     * The thread a request somebody is watching runs on — creating a topic.
     *
     * <p>Not {@link #io()}, for {@link #authIo()}'s reason: the request can wait twenty seconds on
     * a slow server, and every screen's reads queue on that one thread. Not {@code authIo} either,
     * which says what it is for in its name and is worth keeping that way. Writing the answer to
     * Room from here is safe without ordering against {@code io}: it adds a row nobody is editing,
     * and the screens learn of it through invalidation like any other write.
     */
    public static Executor remoteIo() {
        if (remoteIo == null) {
            synchronized (Graph.class) {
                if (remoteIo == null) {
                    remoteIo = Executors.newSingleThreadExecutor(runnable -> {
                        Thread thread = new Thread(runnable, "flashcards-remote");
                        // Daemon for io's reason. A create still waiting when the last screen goes
                        // either lands on the server or does not; nothing here is half-written.
                        thread.setDaemon(true);
                        return thread;
                    });
                }
            }
        }
        return remoteIo;
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

    /**
     * Points {@link #topicCreator} at a server of the caller's choosing.
     *
     * <p>For installDatabase's reason: a test driving the real sheet from the real toolbar has no
     * other way to reach the client it builds, and the build's own base URL is the deployed
     * instance. Production never calls this, and {@link #reset()} puts it back.
     */
    @VisibleForTesting
    public static void installApi(FlashcardsApi installed) {
        api = installed;
    }

    @VisibleForTesting
    public static void reset() {
        database = null;
        api = null;
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

    public static CardRepository cards(Context context) {
        return new CardRepository(database(context));
    }

    /**
     * Reading the band, accepting and discarding. No API client, which is what lets the card
     * list work on a build with no key — none of those three things touches a network.
     */
    public static CandidateRepository candidates(Context context) {
        return new CandidateRepository(database(context));
    }

    /**
     * The same repository with the means to ask for a batch. Generating is the only thing in
     * this app that has to reach a server, so this is the only accessor here that builds a
     * card API client.
     *
     * <p>The split from {@link #candidates} predates tokens and outlives them. It existed
     * because the API key was refused at construction, so one accessor would have taken the
     * whole card list down on a build with no key; it stays because reading, accepting and
     * discarding a candidate genuinely do not touch a network, and an accessor that says so is
     * worth more than one that is merely shorter.
     */
    public static CandidateRepository generator(Context context) {
        return new CandidateRepository(
                database(context), ApiClient.create(context), Clock.systemDefaultZone());
    }

    /**
     * Adding a topic. Built per call, like {@link #generator}, so the client it carries is only
     * ever made by somebody who is about to use it.
     */
    public static TopicRepository topicCreator(Context context) {
        FlashcardsApi installed = api;
        return new TopicRepository(database(context),
                installed != null ? installed : ApiClient.create(context));
    }

    /**
     * The tokens, shared by every client and every screen in the process — the same instance,
     * so a screen watching for a change sees the one the sync's authenticator wrote.
     */
    public static TokenStore tokens(Context context) {
        return PrefsTokenStore.get(context);
    }

    /** Signing in and out. Built on the bare client, which carries no credential of its own. */
    public static AuthRepository auth(Context context) {
        return new AuthRepository(ApiClient.auth(), tokens(context));
    }
}
