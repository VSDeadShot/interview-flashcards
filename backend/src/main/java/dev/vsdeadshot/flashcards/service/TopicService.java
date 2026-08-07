package dev.vsdeadshot.flashcards.service;

import dev.vsdeadshot.flashcards.domain.Topic;
import dev.vsdeadshot.flashcards.repository.TopicRepository;
import java.time.Clock;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Topic reads and writes.
 *
 * <p>{@code userId} is a parameter on every method rather than something this class reaches
 * out for. The service therefore has no opinion about how a caller was authenticated, and the
 * ownership filter stays in the query where it cannot be skipped.
 *
 * <p>Entities are returned rather than DTOs, which the web layer will introduce. With
 * {@code open-in-view=false} that is safe only because callers read {@code topic.getId()} and
 * scalar fields; anything that navigates a lazy association must do so inside a transaction.
 */
@Service
public class TopicService {

    private final TopicRepository topics;
    private final Clock clock;

    public TopicService(TopicRepository topics, Clock clock) {
        this.topics = topics;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Topic> list(String userId) {
        return topics.findByUserIdOrderByNameAsc(userId);
    }

    @Transactional(readOnly = true)
    public Topic get(String userId, long id) {
        return topics.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new NotFoundException("topic", id));
    }

    /**
     * Creates a topic, deriving its slug from {@code name}.
     *
     * @throws IllegalArgumentException when the name contains nothing a slug can be built
     *                                  from; the web layer maps this to {@code 400}
     * @throws DuplicateTopicException  when this user already has that slug
     */
    @Transactional
    public Topic create(String userId, String name) {
        String trimmed = name == null ? "" : name.strip();
        String slug = Slugs.slugify(trimmed);
        if (slug.isEmpty()) {
            throw new IllegalArgumentException(
                    "name must contain at least one letter or digit, was '" + trimmed + "'");
        }

        // Checked first so the common case gets a clear error instead of a constraint
        // violation, but the check alone is not the guarantee — see below.
        if (topics.findByUserIdAndSlug(userId, slug).isPresent()) {
            throw new DuplicateTopicException(slug);
        }

        try {
            return topics.save(new Topic(userId, trimmed, slug, clock.instant()));
        } catch (DataIntegrityViolationException e) {
            // Two concurrent creates can both pass the check above. uq_topic_user_slug is
            // what actually prevents the duplicate, so the loser of that race is translated
            // into the same failure it would have got a moment earlier.
            throw new DuplicateTopicException(slug);
        }
    }
}
