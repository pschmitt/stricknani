package blue.anika.wolle.notify

/** Pure text helpers shared by the notifiers; nothing here needs a Context. */
private const val DETAIL_SEPARATOR = " · "
private const val MINUTES_PER_HOUR = 60L
private const val MILLIS_PER_MINUTE = 60_000L
private const val NOTES_MAX_LINES = 8
private const val NOTES_MAX_CHARS = 600

/** Joins the non-blank [parts] with a middle dot: `name.tar.gz · 2.4 MB`. */
fun joinDetails(vararg parts: String?): String =
    parts.filterNot { it.isNullOrBlank() }.joinToString(DETAIL_SEPARATOR)

/** A duration split for display; whole minutes, never negative. */
data class ElapsedParts(val hours: Long, val minutes: Long)

fun elapsedParts(millis: Long): ElapsedParts {
    val totalMinutes = (millis.coerceAtLeast(0) / MILLIS_PER_MINUTE)
    return ElapsedParts(totalMinutes / MINUTES_PER_HOUR, totalMinutes % MINUTES_PER_HOUR)
}

/** Whether a version string names a release (`1.6.0`) or a rolling build (a short commit). */
enum class VersionKind {
    Release,
    Build,
}

private val RELEASE_VERSION = Regex("""^v?\d+\.\d+\.\d+$""")

fun versionKind(version: String): VersionKind =
    if (RELEASE_VERSION.matches(version.trim())) VersionKind.Release else VersionKind.Build

/** `v1.6.0` -> `1.6.0`: release versions are shown without the tag prefix. */
fun displayVersion(version: String): String =
    version.trim().let { if (versionKind(it) == VersionKind.Release) it.removePrefix("v") else it }

private val MARKDOWN_LINK = Regex("""\[([^\]]*)]\([^)]*\)""")
private val BARE_URL = Regex("""https?://\S+""")
private val LEADING_MARK = Regex("""^\s*(?:#{1,6}\s*|[-*+]\s+|>\s*|\d+[.)]\s+)""")
private val CHANGELOG_FOOTER =
    Regex("""^(?:\*\*)?full changelog(?:\*\*)?:?.*$""", RegexOption.IGNORE_CASE)

/**
 * A short plain-text version of a GitHub release body for a notification: headings and list marks
 * are dropped (list items become `•` lines), links keep their text, URLs and the generated "Full
 * Changelog" footer go, and the result is cut to a few lines. Null when nothing readable is left.
 */
fun releaseNotesSummary(
    body: String?,
    maxLines: Int = NOTES_MAX_LINES,
    maxChars: Int = NOTES_MAX_CHARS,
): String? {
    if (body.isNullOrBlank()) return null
    val lines =
        body
            .lineSequence()
            .map { it.trim() }
            .filterNot { CHANGELOG_FOOTER.matches(it) }
            .map { raw ->
                val bullet = raw.matches(Regex("""^[-*+]\s+.*"""))
                val text =
                    raw.replace(MARKDOWN_LINK, "$1")
                        .replace(BARE_URL, "")
                        .replace(LEADING_MARK, "")
                        .replace("**", "")
                        .replace("__", "")
                        .replace("`", "")
                        .trim()
                if (bullet && text.isNotEmpty()) "• $text" else text
            }
            .filter { it.isNotEmpty() }
            .take(maxLines)
            .toList()
    if (lines.isEmpty()) return null
    val joined = lines.joinToString("\n")
    return if (joined.length <= maxChars) joined else joined.take(maxChars - 1).trimEnd() + "…"
}
