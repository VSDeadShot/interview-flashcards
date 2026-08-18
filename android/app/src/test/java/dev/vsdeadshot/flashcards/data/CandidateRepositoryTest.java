package dev.vsdeadshot.flashcards.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import androidx.room.Room;
import dev.vsdeadshot.flashcards.data.local.CandidateEntity;
import dev.vsdeadshot.flashcards.data.local.CardEntity;
import dev.vsdeadshot.flashcards.data.local.FlashcardsDatabase;
import dev.vsdeadshot.flashcards.data.local.TopicEntity;
import java.time.Instant;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Real SQLite in memory, for the same reason the backend runs real Postgres: an in-memory
 * stand-in would accept things the device's database rejects.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class CandidateRepositoryTest {

    private FlashcardsDatabase db;
    private CandidateRepository candidates;

    @Before
    public void openDatabase() {
        db = Room.inMemoryDatabaseBuilder(
                        RuntimeEnvironment.getApplication(), FlashcardsDatabase.class)
                .allowMainThreadQueries()
                .build();
        candidates = new CandidateRepository(db);

        TopicEntity topic = new TopicEntity();
        topic.id = 1L;
        topic.name = "DBMS";
        topic.slug = "dbms";
        topic.createdAt = Instant.parse("2026-08-01T09:00:00Z");
        db.topics().upsertAll(List.of(topic));
    }

    @After
    public void closeDatabase() {
        db.close();
    }

    private static CandidateEntity candidate(String front, String back) {
        CandidateEntity entity = new CandidateEntity();
        entity.topicId = 1L;
        entity.front = front;
        entity.back = back;
        entity.generatedAt = Instant.parse("2026-08-18T10:00:00Z");
        return entity;
    }

    @Test
    public void storedCandidatesComeBackInTheOrderTheModelProducedThem() {
        candidates.store(1L, List.of(candidate("Q1", "A1"), candidate("Q2", "A2")));

        List<CandidateEntity> stored = candidates.all();

        assertEquals("both candidates should be stored", 2, stored.size());
        assertEquals("the first candidate should come back first", "Q1", stored.get(0).front);
        assertEquals("the second should follow it", "Q2", stored.get(1).front);
    }

    @Test
    public void acceptingACandidateCreatesACardAndRemovesTheCandidate() {
        candidates.store(1L, List.of(candidate("Q1", "A1")));
        long id = candidates.all().get(0).id;

        CardEntity created = candidates.accept(id);

        assertNotNull("accepting should produce a card", created);
        assertEquals("the card should carry the candidate's question", "Q1", created.front);
        assertEquals("and its answer", "A1", created.back);
        assertNull("the candidate row must not survive being accepted", db.candidates().find(id));
    }

    @Test
    public void anAcceptedCandidateBecomesAnUnsyncedCardTheOutboxWillSend() {
        candidates.store(1L, List.of(candidate("Q1", "A1")));

        CardEntity created = candidates.accept(candidates.all().get(0).id);

        assertNull("a card written here has no server id until its create is accepted",
                created.serverId);
        assertNotNull("it needs a client id so a retried create cannot duplicate it",
                created.clientCardId);
        assertTrue("the sync should be offered it as a pending create",
                db.cards().pendingCreates().stream().anyMatch(card -> card.id == created.id));
    }

    @Test
    public void acceptingAnUnknownCandidateChangesNothing() {
        candidates.store(1L, List.of(candidate("Q1", "A1")));

        CardEntity created = candidates.accept(9_999L);

        assertNull("there is nothing to accept", created);
        assertEquals("the real candidate should be untouched", 1, db.candidates().count());
        assertEquals("and no card should have been written", 0, db.cards().pendingCreates().size());
    }

    @Test
    public void discardingRemovesOnlyThatCandidate() {
        candidates.store(1L, List.of(candidate("Q1", "A1"), candidate("Q2", "A2")));
        long first = candidates.all().get(0).id;

        candidates.discard(first);

        List<CandidateEntity> left = candidates.all();
        assertEquals("only one should have gone", 1, left.size());
        assertEquals("and it should be the other one that remains", "Q2", left.get(0).front);
        assertEquals("discarding must never write a card", 0, db.cards().pendingCreates().size());
    }

    @Test
    public void discardingAllEmptiesTheBand() {
        candidates.store(1L, List.of(candidate("Q1", "A1"), candidate("Q2", "A2")));

        candidates.discardAll();

        assertTrue("discarding all should leave nothing", candidates.all().isEmpty());
    }

    @Test
    public void storingAFreshBatchReplacesTheOneBefore() {
        candidates.store(1L, List.of(candidate("Old", "A")));
        candidates.store(1L, List.of(candidate("New", "A")));

        List<CandidateEntity> stored = candidates.all();

        assertEquals("a new generation replaces the previous batch rather than adding to it",
                1, stored.size());
        assertEquals("only the newest batch should remain", "New", stored.get(0).front);
    }

    @Test
    public void candidatesAreInvisibleToTheStudyQueue() {
        candidates.store(1L, List.of(candidate("Q1", "A1")));

        assertTrue("a candidate is not a card until somebody accepts it",
                db.cards().queue(java.time.LocalDate.parse("2026-08-18"), 20).isEmpty());
    }
}
