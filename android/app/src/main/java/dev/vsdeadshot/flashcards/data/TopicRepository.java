package dev.vsdeadshot.flashcards.data;

import dev.vsdeadshot.flashcards.data.local.FlashcardsDatabase;
import dev.vsdeadshot.flashcards.data.local.TopicEntity;
import dev.vsdeadshot.flashcards.data.remote.FlashcardsApi;
import dev.vsdeadshot.flashcards.data.remote.dto.CreateTopicRequestDto;
import java.io.IOException;
import java.util.List;

/**
 * Creating a topic, which only the server can do.
 *
 * <p><strong>Online only, and that is the schema's doing rather than a shortcut.</strong> A topic
 * is cached under the server's id — there is no local id and no {@code serverId} column, unlike a
 * card — so a topic cannot exist here before the server has answered with one. Giving topics an
 * outbox would mean giving them the whole of the card's two-id machinery, for something created a
 * handful of times in the life of a deck.
 *
 * <p>Foreground work like generation: somebody pressed a button and is waiting, so a failure is
 * theirs to see rather than something queued and retried behind them. Blocking, like every
 * repository here; the caller picks the thread.
 */
public final class TopicRepository {

    private final FlashcardsDatabase db;
    private final FlashcardsApi api;

    public TopicRepository(FlashcardsDatabase db, FlashcardsApi api) {
        this.db = db;
        this.api = api;
    }

    /**
     * Asks the server for the topic and caches what it answered with.
     *
     * <p>Written to the cache straight away rather than left for the next pull, so the topic is
     * on screen — as a filter chip, in the editor's picker — the moment the request succeeds. The
     * pull would bring the same row down anyway; it is keyed by the server's id, so the two writes
     * cannot produce two rows.
     *
     * @throws dev.vsdeadshot.flashcards.data.remote.ApiException if the server refused, including
     *         the {@code 409} for a name this user already has
     * @throws IOException if the request never got an answer
     */
    public TopicEntity create(String name) throws IOException {
        // The server strips too, but only after @Size has measured the raw value — so a name
        // that fits, padded by a stray space or newline, could be refused as too long.
        TopicEntity created = Mappers.toEntity(
                api.createTopic(new CreateTopicRequestDto(name.strip())).execute().body());
        db.topics().upsertAll(List.of(created));
        return created;
    }
}
