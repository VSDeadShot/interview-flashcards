package dev.vsdeadshot.flashcards.data.local;

import java.time.Instant;

/**
 * One card's line in a listing, with its topic already attached.
 *
 * <p>A plain class rather than a record because Room constructs it from a cursor, matching every
 * other row-mapped type here.
 *
 * <p>The card's text is only its front. The listing is something to scan, and the answer is what
 * the editor is for — so the query does not carry it and a long back does not cost a row it is
 * never shown on.
 */
public class CardSummaryRow {

    public long id;

    public String front;

    /**
     * What the card is filed under, always - unlike {@link #topicName}, which is null when the
     * cache does not hold the topic. Filtering a listing keys on this rather than on the name,
     * so a card whose topic has not been pulled yet still filters correctly.
     */
    public long topicId;

    /** Null when the cache does not hold the card's topic — a listing shows the card regardless. */
    public String topicName;

    public Long serverId;

    public Instant pendingSince;

    public String syncError;

    /**
     * Whether the server has been refused this card, permanently.
     *
     * <p>Checked before {@link #unsent()} by anything showing one label, because a rejected card
     * is also unsent and only this one is worth acting on: it will not be offered again until the
     * card is edited.
     */
    public boolean rejected() {
        return syncError != null;
    }

    /**
     * Whether the server does not have this row as it stands here — either because the card was
     * written on this device and never created, or because it was changed since it last synced.
     *
     * <p>Deliberately one answer rather than two. Which of those it is makes no difference to
     * somebody reading a list: both mean the server has not caught up, and both clear themselves
     * on the next run.
     */
    public boolean unsent() {
        return serverId == null || pendingSince != null;
    }
}
