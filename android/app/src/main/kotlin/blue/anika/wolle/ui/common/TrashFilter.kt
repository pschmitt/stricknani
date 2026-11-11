package blue.anika.wolle.ui.common

import blue.anika.wolle.data.util.DateTimeUtils
import kotlin.math.ceil

/** The value of the `trashed` navigation argument / URL parameter that lists only trashed items. */
const val TRASHED_ONLY = "only"

private const val DAY_MILLIS = 86_400_000.0

/**
 * Whole days until the trash is emptied for an item, at least 1 while it is still there; null for
 * an item that is not in the trash or has no (readable) purge time. Same rule as the website's
 * `trashDaysLeft`.
 */
fun trashDaysLeft(trashedAt: String?, purgeAt: String?, nowMillis: Long): Int? {
    if (trashedAt == null || purgeAt == null) return null
    val purge = runCatching { DateTimeUtils.parseToEpochMillis(purgeAt) }.getOrNull() ?: return null
    return maxOf(1, ceil((purge - nowMillis) / DAY_MILLIS).toInt())
}
