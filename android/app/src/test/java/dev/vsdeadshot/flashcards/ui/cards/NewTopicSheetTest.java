package dev.vsdeadshot.flashcards.ui.cards;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.work.WorkManager;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.textfield.TextInputEditText;
import dev.vsdeadshot.flashcards.R;
import dev.vsdeadshot.flashcards.data.auth.FakeTokenStore;
import dev.vsdeadshot.flashcards.data.remote.ApiClient;
import dev.vsdeadshot.flashcards.data.sync.SyncScheduler;
import dev.vsdeadshot.flashcards.ui.Graph;
import dev.vsdeadshot.flashcards.ui.MainActivity;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.robolectric.RuntimeEnvironment;

/**
 * The sheet as it is actually reached — the overflow item on the real card list — talking to a
 * real server on a loopback port.
 *
 * <p>The cache comes from {@link CardListTestSupport}, which holds "Operating Systems" as topic 1;
 * the API comes from {@code Graph.installApi}, for installDatabase's reason. A failure is drawn
 * <em>inside</em> the sheet, like generation's, because every answer to it — fix the name, turn the
 * radio on, sign in, try again — needs the name that was typed to still be there.
 */
public class NewTopicSheetTest extends CardListTestSupport {

    private static final String CREATED = "{\"id\":9,\"name\":\"Modern C++\","
            + "\"slug\":\"modern-c\",\"createdAt\":\"2026-10-01T09:00:00Z\"}";

    private static final String DUPLICATE = "{\"status\":409,\"title\":\"Duplicate topic\","
            + "\"detail\":\"topic slug 'operating-systems' already exists\","
            + "\"slug\":\"operating-systems\"}";

    private MockWebServer server;

    @Before
    public void startServer() throws IOException {
        server = new MockWebServer();
        server.start();
        Graph.installApi(ApiClient.create(server.url("/api/v1/").toString(),
                FakeTokenStore.accessOnly("token")));
    }

    @After
    public void stopServer() {
        server.close();
    }

    @Test
    public void theOverflowItemOpensASheetAskingForAName() throws Exception {
        NewTopicSheet sheet = openSheet(openActivity());

        assertNotNull("the sheet asks for one thing, the name",
                sheet.requireView().findViewById(R.id.new_topic_name));
    }

    @Test
    public void createIsOfferedOnlyOnceTheNameHasSomethingInIt() throws Exception {
        NewTopicSheet sheet = openSheet(openActivity());
        View create = sheet.requireView().findViewById(R.id.new_topic_create);

        assertFalse("an empty name has nothing to send", create.isEnabled());
        type(sheet, "   \n ");
        assertFalse("nor does one that is only whitespace -- the server would answer 400",
                create.isEnabled());
        type(sheet, "Modern C++");
        assertTrue("a real name can be sent", create.isEnabled());
    }

    @Test
    public void aCreatedTopicClosesTheSheetAndAppearsAsAFilterChip() throws Exception {
        respond(201, "application/json", CREATED);
        MainActivity activity = openActivity();
        NewTopicSheet sheet = openSheet(activity);

        type(sheet, "Modern C++");
        sheet.requireView().findViewById(R.id.new_topic_create).performClick();

        assertTrue("the sheet's job is over once the topic exists",
                eventually(() -> !sheet.isAdded()));
        assertTrue("the new topic should be a chip on the card list without waiting for a sync",
                eventually(() -> hasChip(activity, "Modern C++")));
    }

    @Test
    public void aRefusalIsShownInTheSheetWithTheNameStillThere() throws Exception {
        respond(409, "application/problem+json", DUPLICATE);
        NewTopicSheet sheet = openSheet(openActivity());

        type(sheet, "Operating Systems");
        sheet.requireView().findViewById(R.id.new_topic_create).performClick();

        View view = sheet.requireView();
        TextView error = view.findViewById(R.id.new_topic_error);
        assertTrue("the refusal should be on screen",
                eventually(() -> error.getVisibility() == View.VISIBLE));
        assertEquals("and say the name is already taken",
                sheet.getString(R.string.new_topic_error_duplicate), error.getText().toString());
        assertTrue("the sheet stays open, so the name can be changed", sheet.isAdded());
        assertEquals("the name survives, so fixing it is an edit rather than retyping",
                "Operating Systems", text(sheet));
        assertTrue("and the button comes back, or there is no trying again",
                view.findViewById(R.id.new_topic_create).isEnabled());
    }

    /**
     * A 409 can mean this device simply does not have the topic yet — a create whose answer was
     * lost, or one made on another device. A sync is what brings it here.
     */
    @Test
    public void aNameAlreadyTakenAsksForASyncToBringThatTopicHere() throws Exception {
        respond(409, "application/problem+json", DUPLICATE);
        NewTopicSheet sheet = openSheet(openActivity());

        type(sheet, "Operating Systems");
        sheet.requireView().findViewById(R.id.new_topic_create).performClick();
        TextView error = sheet.requireView().findViewById(R.id.new_topic_error);
        assertTrue(eventually(() -> error.getVisibility() == View.VISIBLE));

        assertFalse("the duplicate should have asked for a sync",
                WorkManager.getInstance(RuntimeEnvironment.getApplication())
                        .getWorkInfosForUniqueWork(SyncScheduler.IMMEDIATE_WORK).get().isEmpty());
    }

    /**
     * The request can wait twenty seconds on a slow server. Queued on {@code Graph.io()} it would
     * hold up every screen's reads for as long, which is the mistake sign-in made and fixed.
     */
    @Test
    public void theRequestDoesNotQueueBehindTheCache() throws Exception {
        respond(201, "application/json", CREATED);
        NewTopicSheet sheet = openSheet(openActivity());
        type(sheet, "Modern C++");

        CountDownLatch release = new CountDownLatch(1);
        Graph.io().execute(() -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            sheet.requireView().findViewById(R.id.new_topic_create).performClick();
            assertNotNull("the request must reach the server while the cache's thread is busy",
                    server.takeRequest(3, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            settle();
        }
    }

    // ---- fixtures -----------------------------------------------------------------------------

    private void respond(int code, String contentType, String body) {
        server.enqueue(new MockResponse.Builder()
                .code(code)
                .setHeader("Content-Type", contentType)
                .body(body)
                .build());
    }

    private NewTopicSheet openSheet(MainActivity activity) throws InterruptedException {
        Toolbar toolbar = activity.findViewById(R.id.toolbar);
        assertNotNull("the card list has to put the action on the toolbar",
                toolbar.getMenu().findItem(R.id.action_new_topic));
        toolbar.getMenu().performIdentifierAction(R.id.action_new_topic, 0);
        settle();

        Fragment host = activity.getSupportFragmentManager().findFragmentById(R.id.nav_host);
        Fragment list = host.getChildFragmentManager().getFragments().get(0);
        NewTopicSheet sheet = (NewTopicSheet)
                list.getParentFragmentManager().findFragmentByTag(NewTopicSheet.TAG);
        assertNotNull("tapping the action should have shown the sheet", sheet);
        return sheet;
    }

    private static void type(NewTopicSheet sheet, String name) {
        ((TextInputEditText) sheet.requireView().findViewById(R.id.new_topic_name)).setText(name);
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static String text(NewTopicSheet sheet) {
        return ((TextInputEditText) sheet.requireView().findViewById(R.id.new_topic_name))
                .getText().toString();
    }

    private static boolean hasChip(MainActivity activity, String name) {
        ChipGroup chips = activity.findViewById(R.id.cards_filter);
        for (int i = 0; i < chips.getChildCount(); i++) {
            if (name.contentEquals(((Chip) chips.getChildAt(i)).getText())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Polls with the main looper running, without moving its clock. The answer crosses three
     * threads — the request's, Room's invalidation, and the cache's reload — none of which this
     * test has a handle to await, so a bounded poll is the honest wait.
     */
    private static boolean eventually(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle();
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }
}
