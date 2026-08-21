package blue.anika.wolle.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import blue.anika.wolle.data.backup.BackupFrequency
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One bottom-nav destination's persisted visibility/order (SNA-16) - [id] is
 * `TopLevelDestination.name`, kept as a plain string here since the data layer doesn't know about
 * `ui.navigation` types; `NavBarViewModel`/`SettingsViewModel` do the id <-> enum mapping and
 * sanitize against the current set of destinations (dropping unknown ids, appending new ones).
 */
@Serializable data class NavbarItemPreference(val id: String, val visible: Boolean = true)

/**
 * Non-secret, non-sync app preferences (SNA-18's theme toggle, SNA-16's navbar customization) -
 * DataStore rather than `SettingsRepository`'s `EncryptedSharedPreferences`, since nothing here
 * needs Keystore-backed encryption. Sync bookkeeping (SNA-7) stays in Room instead - see
 * `SyncStateEntity`'s kdoc.
 */
@Singleton
class AppPreferencesRepository
@Inject
constructor(private val dataStore: DataStore<Preferences>, private val json: Json) {

    val themeMode =
        dataStore.data.map { prefs ->
            prefs[KEY_THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM
        }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { prefs -> prefs[KEY_THEME_MODE] = mode.name }
    }

    /** Null means "never customized" - the caller falls back to its own default order. */
    val navbarItems =
        dataStore.data.map { prefs ->
            prefs[KEY_NAVBAR_ITEMS]?.let { raw ->
                runCatching { json.decodeFromString<List<NavbarItemPreference>>(raw) }.getOrNull()
            }
        }

    suspend fun setNavbarItems(items: List<NavbarItemPreference>) {
        dataStore.edit { prefs -> prefs[KEY_NAVBAR_ITEMS] = json.encodeToString(items) }
    }

    /** SNA-15 scheduled backups - the SAF tree the user picked, `null` until one is chosen. */
    val scheduledBackupFolderUri = dataStore.data.map { prefs -> prefs[KEY_BACKUP_FOLDER_URI] }

    suspend fun setScheduledBackupFolderUri(uri: String) {
        dataStore.edit { prefs -> prefs[KEY_BACKUP_FOLDER_URI] = uri }
    }

    val scheduledBackupEnabled = dataStore.data.map { prefs -> prefs[KEY_BACKUP_ENABLED] ?: false }

    suspend fun setScheduledBackupEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_BACKUP_ENABLED] = enabled }
    }

    val scheduledBackupFrequency =
        dataStore.data.map { prefs ->
            prefs[KEY_BACKUP_FREQUENCY]?.let {
                runCatching { BackupFrequency.valueOf(it) }.getOrNull()
            } ?: BackupFrequency.WEEKLY
        }

    suspend fun setScheduledBackupFrequency(frequency: BackupFrequency) {
        dataStore.edit { prefs -> prefs[KEY_BACKUP_FREQUENCY] = frequency.name }
    }

    /** SNA-34's tap-to-unlock easter egg, matching syncwich's `developer_mode` flag. */
    val developerMode = dataStore.data.map { prefs -> prefs[KEY_DEVELOPER_MODE] ?: false }

    suspend fun setDeveloperMode(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_DEVELOPER_MODE] = enabled }
    }

    /**
     * SNA-14: prevents re-prompting for notification permission after the user has answered once.
     */
    val notificationPermissionRequested =
        dataStore.data.map { prefs -> prefs[KEY_NOTIFICATION_PERMISSION_REQUESTED] ?: false }

    suspend fun markNotificationPermissionRequested() {
        dataStore.edit { prefs -> prefs[KEY_NOTIFICATION_PERMISSION_REQUESTED] = true }
    }

    private companion object {
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_NAVBAR_ITEMS = stringPreferencesKey("navbar_items")
        val KEY_BACKUP_FOLDER_URI = stringPreferencesKey("scheduled_backup_folder_uri")
        val KEY_BACKUP_ENABLED = booleanPreferencesKey("scheduled_backup_enabled")
        val KEY_BACKUP_FREQUENCY = stringPreferencesKey("scheduled_backup_frequency")
        val KEY_DEVELOPER_MODE = booleanPreferencesKey("developer_mode")
        val KEY_NOTIFICATION_PERMISSION_REQUESTED =
            booleanPreferencesKey("notification_permission_requested")
    }
}
