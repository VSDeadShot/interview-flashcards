package dev.vsdeadshot.flashcards.data.remote;

import com.squareup.moshi.FromJson;
import com.squareup.moshi.ToJson;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The three types the contract uses that Moshi has no built-in adapter for.
 *
 * <p>All three are ISO-8601 or its UUID equivalent, which is what Jackson writes on the other
 * end, so each is its own {@code toString} and its own {@code parse}. Nothing here formats by
 * hand: an {@link Instant} printed with a pattern would lose the distinction between a whole
 * second and a fractional one, and the server sends both.
 *
 * <p>{@code minSdk 26} is what makes this possible without desugaring — the client parses
 * dates with the same {@code java.time} the scheduler computes them in.
 */
final class JsonTimeAdapters {

    @ToJson
    String instantToJson(Instant value) {
        return value.toString();
    }

    @FromJson
    Instant instantFromJson(String value) {
        return Instant.parse(value);
    }

    @ToJson
    String localDateToJson(LocalDate value) {
        return value.toString();
    }

    @FromJson
    LocalDate localDateFromJson(String value) {
        return LocalDate.parse(value);
    }

    @ToJson
    String uuidToJson(UUID value) {
        return value.toString();
    }

    @FromJson
    UUID uuidFromJson(String value) {
        return UUID.fromString(value);
    }
}
