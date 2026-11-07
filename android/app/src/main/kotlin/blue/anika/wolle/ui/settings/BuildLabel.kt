package blue.anika.wolle.ui.settings

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * What About shows for the running build: `v1.4.0 (latest, debug)`. The source is
 * `BuildConfig.RELEASE_CHANNEL` (`latest`, `stable`, `local`, `play`); a blank value is left out.
 * The words are the same in every language.
 */
internal fun appVersionLabel(versionName: String, releaseChannel: String, debug: Boolean): String {
    val source = releaseChannel.trim().takeIf { it.isNotEmpty() }
    val type = if (debug) "debug" else "release"
    return "v$versionName (${listOfNotNull(source, type).joinToString(", ")})"
}

/**
 * `BuildConfig.BUILD_DATE` (ISO-8601 instant) in the user's locale and time zone, date and time in
 * medium style; null when the APK carries no usable date.
 */
internal fun formatBuildDate(
    raw: String?,
    locale: Locale = Locale.getDefault(),
    zone: ZoneId = ZoneId.systemDefault(),
): String? {
    val instant =
        raw?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }
    return instant?.let {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.MEDIUM)
            .withLocale(locale)
            .withZone(zone)
            .format(it)
    }
}
