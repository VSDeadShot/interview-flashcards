package dev.vsdeadshot.flashcards.data.local;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;
import java.time.LocalDate;

/**
 * How many reviews were answered on this device, per day.
 *
 * <p>Kept because the outbox cannot answer the question: a row there is deleted the moment the
 * server accepts it, so by the evening the count of what was done that morning is gone. The
 * alternative of counting cards whose {@code lastReviewedAt} falls today undercounts, since a
 * card answered twice in a day — a lapse and then a retry — is one row either way.
 *
 * <p>It counts <em>this device's</em> reviews, so a second device's work is missing until the
 * server is asked. That is a much smaller error than the one the streak would make locally, and
 * it is why this is computed here while the streak is not: an undercount of a number that resets
 * at midnight, against telling someone they broke a run they did not.
 */
@Entity(tableName = "review_tally")
public class ReviewTallyEntity {

    /**
     * Initialised with {@code ofEpochDay(0)} rather than {@code LocalDate.EPOCH}, which is API 34
     * and would compile here and then throw on any device below it. The value is never used —
     * Room needs a non-null field and every row sets its own day.
     */
    @PrimaryKey
    @NonNull
    public LocalDate day = LocalDate.ofEpochDay(0);

    public int reviews;
}
