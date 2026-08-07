package dev.vsdeadshot.flashcards.data.local;

import androidx.room.TypeConverter;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * How the contract's types are stored.
 *
 * <p>Instants are epoch milliseconds and dates are epoch days, rather than text. Both are
 * integers, which means "due on or before today" is an integer comparison SQLite can index —
 * and the study queue is the query this whole cache exists to answer.
 *
 * <p>Milliseconds are enough precision. The server keeps microseconds and truncates what it is
 * sent, so a client that stores milliseconds sends the same value on every retry of a review,
 * which is exactly what the idempotency check compares.
 */
public final class Converters {

    private Converters() {
    }

    @TypeConverter
    public static Long fromInstant(Instant value) {
        return value == null ? null : value.toEpochMilli();
    }

    @TypeConverter
    public static Instant toInstant(Long value) {
        return value == null ? null : Instant.ofEpochMilli(value);
    }

    @TypeConverter
    public static Long fromLocalDate(LocalDate value) {
        return value == null ? null : value.toEpochDay();
    }

    @TypeConverter
    public static LocalDate toLocalDate(Long value) {
        return value == null ? null : LocalDate.ofEpochDay(value);
    }

    @TypeConverter
    public static String fromUuid(UUID value) {
        return value == null ? null : value.toString();
    }

    @TypeConverter
    public static UUID toUuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }
}
