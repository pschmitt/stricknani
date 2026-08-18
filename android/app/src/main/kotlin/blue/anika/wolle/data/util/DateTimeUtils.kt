package blue.anika.wolle.data.util

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/**
 * Parses a wire timestamp from the Stricknani API into epoch millis.
 *
 * The backend's `datetime` columns are stored as naive UTC (SQLite has no real datetime type),
 * so a Pydantic-serialized naive value comes across with no offset/`Z` suffix (confirmed via a
 * direct `datetime.now()` -> `.isoformat()` check against the actual backend), while
 * `server_time` (built from `datetime.now(UTC)`) comes across as offset-aware. Both are handled
 * here rather than relying on a single strict ISO8601 parser - an offset-naive value is UTC by
 * construction, so it's parsed as [LocalDateTime] and anchored to [ZoneOffset.UTC].
 */
object DateTimeUtils {
    fun parseToEpochMillis(raw: String): Long =
        try {
            OffsetDateTime.parse(raw).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC).toEpochMilli()
        }
}
