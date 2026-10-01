package dev.vsdeadshot.flashcards.ui;

import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/**
 * The background threads, and the one property that makes them more than one.
 *
 * <p>No Robolectric: the executors touch nothing in the framework.
 */
public class GraphTest {

    /**
     * Signing in waits on the network, for as long as a server waking from idle takes to answer.
     * Every screen reads the cache on {@link Graph#io()}, one thread on purpose, so a sign-in
     * queued there would hold up every read in the app for the whole of that wait — pressing back
     * to the study screen mid sign-in would show a screen that never loads.
     */
    @Test
    public void aSignInWaitingOnTheNetworkDoesNotHoldUpTheCache() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Graph.authIo().execute(() -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        CountDownLatch read = new CountDownLatch(1);
        Graph.io().execute(read::countDown);
        try {
            assertTrue("a read of the cache must not queue behind a sign-in still in flight",
                    read.await(2, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    /**
     * Creating a topic waits on the network too — twenty seconds before it gives up on a server
     * that is slow to answer. Queued on {@link Graph#io()} it would stall every screen's reads
     * for that long, which is the mistake sign-in made and {@link Graph#authIo()} fixed.
     */
    @Test
    public void aRequestWaitingOnTheNetworkDoesNotHoldUpTheCache() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Graph.remoteIo().execute(() -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        CountDownLatch read = new CountDownLatch(1);
        Graph.io().execute(read::countDown);
        try {
            assertTrue("a read of the cache must not queue behind a request still in flight",
                    read.await(2, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    /**
     * Sign-in and sign-out share their own thread, so they happen in the order they were asked
     * for — a sign-out tapped while a sign-in is still waiting runs after it, not beside it.
     */
    @Test
    public void signingInAndOutRunInTheOrderTheyWereAskedFor() throws Exception {
        StringBuffer order = new StringBuffer();
        CountDownLatch done = new CountDownLatch(2);
        Graph.authIo().execute(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            order.append("in,");
            done.countDown();
        });
        Graph.authIo().execute(() -> {
            order.append("out");
            done.countDown();
        });

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertTrue("the sign-out waited for the sign-in ahead of it: " + order,
                order.toString().equals("in,out"));
    }
}
