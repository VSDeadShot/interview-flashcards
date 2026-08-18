package dev.vsdeadshot.flashcards.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vsdeadshot.flashcards.ai.GeminiClient;
import dev.vsdeadshot.flashcards.ai.GeneratedCard;
import dev.vsdeadshot.flashcards.ai.GenerationPrompt;
import dev.vsdeadshot.flashcards.ai.GenerationRefusedException;
import dev.vsdeadshot.flashcards.domain.Card;
import dev.vsdeadshot.flashcards.domain.Topic;
import dev.vsdeadshot.flashcards.repository.CardRepository;
import dev.vsdeadshot.flashcards.repository.TopicRepository;
import dev.vsdeadshot.flashcards.support.EmbeddedPostgresTest;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
@DisplayName("Generating cards for a topic")
class CardGeneratorTest extends EmbeddedPostgresTest {

    private static final String USER = "generator-test";
    private static final String OTHER_USER = "somebody-else";

    @Autowired
    private TopicRepository topics;

    @Autowired
    private CardRepository cards;

    @Autowired
    private Clock clock;

    private Topic topic;

    /** Records the prompt it was handed, so a test can assert on what the model was told. */
    private static final class RecordingClient implements GeminiClient {

        private final List<GeneratedCard> answer;
        private GenerationPrompt seen;

        RecordingClient(List<GeneratedCard> answer) {
            this.answer = answer;
        }

        @Override
        public List<GeneratedCard> generate(GenerationPrompt prompt) {
            this.seen = prompt;
            return answer;
        }
    }

    @BeforeEach
    void createTopic() {
        topic = topics.save(new Topic(USER, "DBMS", "dbms", clock.instant()));
    }

    private RecordingClient clientReturning(GeneratedCard... answer) {
        return new RecordingClient(List.of(answer));
    }

    private CardGenerator generatorFor(RecordingClient client) {
        return new CardGenerator(topics, cards, client);
    }

    private Card saveCard(String front) {
        return cards.save(new Card(USER, topic, front, "An answer",
                LocalDate.now(clock), clock.instant()));
    }

    @Nested
    @DisplayName("choosing how many to ask for")
    class Counting {

        @Test
        @DisplayName("clamps a count above the maximum instead of refusing it")
        void clampsAboveMaximum() {
            RecordingClient client = clientReturning(new GeneratedCard("Q", "A"));

            generatorFor(client).generate(USER, topic.getId(), null, 99);

            assertEquals(10, client.seen.count(),
                    "asking for more than a sitting can hold is not a mistake worth refusing");
        }

        @Test
        @DisplayName("refuses a count of zero or below")
        void refusesZero() {
            RecordingClient client = clientReturning(new GeneratedCard("Q", "A"));
            CardGenerator generator = generatorFor(client);

            assertThrows(IllegalArgumentException.class,
                    () -> generator.generate(USER, topic.getId(), null, 0),
                    "no request means give me no cards other than a bug");
        }

        @Test
        @DisplayName("defaults to eight when no count is given")
        void defaultsToEight() {
            RecordingClient client = clientReturning(new GeneratedCard("Q", "A"));

            generatorFor(client).generate(USER, topic.getId(), null, null);

            assertEquals(8, client.seen.count(), "eight is where reviewing still means reading");
        }
    }

    @Nested
    @DisplayName("deciding what came back is usable")
    class Validating {

        @Test
        @DisplayName("drops an unusable candidate and keeps the rest")
        void dropsUnusableCandidates() {
            CardGenerator generator = generatorFor(clientReturning(
                    new GeneratedCard("Good question", "Good answer"),
                    new GeneratedCard("   ", "No question"),
                    new GeneratedCard("No answer", null)));

            List<GeneratedCard> generated = generator.generate(USER, topic.getId(), null, 3);

            assertEquals(1, generated.size(), "one bad candidate should not cost the good ones");
            assertEquals("Good question", generated.get(0).front(),
                    "the usable candidate should survive");
        }

        @Test
        @DisplayName("drops a candidate too long for the column it would be written to")
        void dropsOverlongCandidates() {
            CardGenerator generator = generatorFor(clientReturning(
                    new GeneratedCard("Fine", "Fine"),
                    new GeneratedCard("x".repeat(10_001), "Too long")));

            List<GeneratedCard> generated = generator.generate(USER, topic.getId(), null, 2);

            assertEquals(1, generated.size(),
                    "a candidate the contract would refuse should never reach the client");
        }

        @Test
        @DisplayName("refuses when every candidate was unusable")
        void refusesWhenAllDropped() {
            CardGenerator generator = generatorFor(clientReturning(new GeneratedCard("", "")));

            assertThrows(GenerationRefusedException.class,
                    () -> generator.generate(USER, topic.getId(), null, 1),
                    "an empty batch is not the same as one worth retrying");
        }
    }

    @Nested
    @DisplayName("telling the model what the deck already covers")
    class AvoidList {

        @Test
        @DisplayName("passes the topic's existing fronts to the model")
        void passesExistingFronts() {
            saveCard("What is 3NF?");
            RecordingClient client = clientReturning(new GeneratedCard("Q", "A"));

            generatorFor(client).generate(USER, topic.getId(), "normalization", 3);

            assertTrue(client.seen.avoid().contains("What is 3NF?"),
                    "the model should be told what the deck already covers");
        }

        @Test
        @DisplayName("leaves archived cards out, so rejected questions do not suppress new ones")
        void excludesArchivedCards() {
            Card retired = saveCard("A question that was archived");
            retired.archive();
            cards.save(retired);
            RecordingClient client = clientReturning(new GeneratedCard("Q", "A"));

            generatorFor(client).generate(USER, topic.getId(), null, 3);

            assertFalse(client.seen.avoid().contains("A question that was archived"),
                    "a retired card is not something a new one would be duplicating");
        }

        @Test
        @DisplayName("passes the topic's name and the caller's focus")
        void passesTopicAndFocus() {
            RecordingClient client = clientReturning(new GeneratedCard("Q", "A"));

            generatorFor(client).generate(USER, topic.getId(), "normalization", 3);

            assertEquals("DBMS", client.seen.topicName(), "the model needs the subject");
            assertEquals("normalization", client.seen.focus(), "the focus narrows the subject");
        }
    }

    @Nested
    @DisplayName("ownership")
    class Ownership {

        @Test
        @DisplayName("reads another user's topic as not found rather than forbidden")
        void anotherUsersTopicIsNotFound() {
            CardGenerator generator = generatorFor(clientReturning(new GeneratedCard("Q", "A")));

            assertThrows(NotFoundException.class,
                    () -> generator.generate(OTHER_USER, topic.getId(), null, 3),
                    "nothing about another user's topic should be discoverable");
        }

        @Test
        @DisplayName("reads an unknown topic as not found")
        void unknownTopicIsNotFound() {
            CardGenerator generator = generatorFor(clientReturning(new GeneratedCard("Q", "A")));

            assertThrows(NotFoundException.class,
                    () -> generator.generate(USER, 999_999L, null, 3),
                    "an id that does not exist is not found, like everywhere else");
        }
    }
}
