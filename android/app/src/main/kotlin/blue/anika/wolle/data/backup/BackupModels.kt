package blue.anika.wolle.data.backup

import blue.anika.wolle.data.settings.NavbarItemPreference
import kotlinx.serialization.Serializable

@Serializable
data class BackupManifest(
    val format: String = "stricknani-backup",
    val version: Int = 1,
    val appId: String = "blue.anika.wolle",
    val createdAtEpochMillis: Long,
)

@Serializable data class BackupCredentials(val serverUrl: String, val apiToken: String)

@Serializable
data class BackupSettings(val themeMode: String, val navbarItems: List<NavbarItemPreference>)

/** How often [BackupWorker] runs when scheduled backups are enabled (SNA-15). */
enum class BackupFrequency(val intervalDays: Long, val label: String) {
    DAILY(1, "Daily"),
    WEEKLY(7, "Weekly"),
    MONTHLY(30, "Monthly"),
}
