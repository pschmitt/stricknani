package blue.anika.wolle.data.backups

import java.util.Locale

/** "1.4 MB": binary steps, one decimal below 10 from KB up. Mirrors `backups-format.ts`. */
fun formatBackupBytes(bytes: Long): String {
    if (bytes < 0) return ""
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var unit = 0
    while (value >= 1024 && unit < units.size - 1) {
        value /= 1024
        unit += 1
    }
    val digits = if (value < 10) 1 else 0
    return String.format(Locale.ROOT, "%.${digits}f %s", value, units[unit])
}

/** A count the backup could not report reads as a dash. */
fun formatBackupCount(value: Long?): String = value?.toString() ?: "–"

/** Newest first; the name breaks ties. */
val backupOrder: Comparator<BackupEntryDto> = Comparator { a, b ->
    val byTime = parseInstantMillis(b.createdAt).compareTo(parseInstantMillis(a.createdAt))
    if (byTime != 0) byTime else a.name.compareTo(b.name)
}

private fun parseInstantMillis(value: String): Long =
    runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrDefault(0L)

enum class CountKey {
    USERS,
    PROJECTS,
    YARNS,
    TRASHED,
}

fun BackupCountsDto?.value(key: CountKey): Long? =
    when (key) {
        CountKey.USERS -> this?.users
        CountKey.PROJECTS -> this?.projects
        CountKey.YARNS -> this?.yarns
        CountKey.TRASHED -> this?.trashed
    }

fun countsDiffer(a: BackupCountsDto?, b: BackupCountsDto?, key: CountKey): Boolean =
    a.value(key) != b.value(key)

/** "Jan 1, 2026, 2:00 AM" in the device zone; the raw text when it does not parse. */
fun formatBackupTime(
    iso: String,
    locale: Locale = Locale.getDefault(),
    zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
): String =
    runCatching {
            java.time.format.DateTimeFormatter.ofLocalizedDateTime(
                    java.time.format.FormatStyle.MEDIUM,
                    java.time.format.FormatStyle.SHORT,
                )
                .withLocale(locale)
                .withZone(zone)
                .format(java.time.Instant.parse(iso))
        }
        .getOrDefault(iso)
