package blue.anika.wolle.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import blue.anika.wolle.BuildConfig
import blue.anika.wolle.data.backup.BackupFrequency
import blue.anika.wolle.data.errorreporting.ERROR_REPORTING_DEFAULT_ENABLED
import blue.anika.wolle.data.errorreporting.ErrorReportingDto
import blue.anika.wolle.data.update.UpdateChannel
import blue.anika.wolle.data.update.UpdateNotice
import blue.anika.wolle.data.update.effectiveUpdateChannel
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.distinctUntilChanged
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

    /**
     * SNA-34's tap-to-unlock easter egg, matching syncwich's `developer_mode` flag. Debug builds
     * start with it on; an explicit off is kept.
     */
    val developerMode =
        dataStore.data.map { prefs -> prefs[KEY_DEVELOPER_MODE] ?: DEVELOPER_MODE_DEFAULT }

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

    /** Version of the update card the user tapped "Later" on - a newer release shows it again. */
    val dismissedUpdateVersion = dataStore.data.map { prefs -> prefs[KEY_DISMISSED_UPDATE] }

    suspend fun dismissUpdate(version: String) {
        dataStore.edit { prefs -> prefs[KEY_DISMISSED_UPDATE] = version }
    }

    /**
     * Which GitHub release stream updates follow: the stored pick, but always stable while
     * developer mode is off (the channel choice is a developer option).
     */
    val updateChannel =
        dataStore.data
            .map { prefs ->
                effectiveUpdateChannel(
                    UpdateChannel.fromName(prefs[KEY_UPDATE_CHANNEL]),
                    prefs[KEY_DEVELOPER_MODE] ?: DEVELOPER_MODE_DEFAULT,
                    debugBuild = BuildConfig.DEBUG,
                )
            }
            .distinctUntilChanged()

    suspend fun setUpdateChannel(channel: UpdateChannel) {
        dataStore.edit { prefs -> prefs[KEY_UPDATE_CHANNEL] = channel.name }
    }

    /** "Update automatically" (GitHub APK builds only): off unless the user turns it on. */
    val autoUpdate = dataStore.data.map { prefs -> prefs[KEY_AUTO_UPDATE] ?: false }

    suspend fun setAutoUpdate(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_AUTO_UPDATE] = enabled }
    }

    /** "Notify me about app updates" (GitHub APK builds only): on unless the user turns it off. */
    val notifyAppUpdates = dataStore.data.map { prefs -> prefs[KEY_NOTIFY_UPDATES] ?: true }

    suspend fun setNotifyAppUpdates(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_NOTIFY_UPDATES] = enabled }
    }

    /** Whether project timers and their local reminders are shown on this device. */
    val projectTimersEnabled =
        dataStore.data.map { prefs -> prefs[KEY_PROJECT_TIMERS_ENABLED] ?: true }

    suspend fun setProjectTimersEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_PROJECT_TIMERS_ENABLED] = enabled }
    }

    /** The last "update available" notification: what, on which channel, when. Null if none. */
    val updateNotice =
        dataStore.data.map { prefs ->
            val version = prefs[KEY_NOTICE_VERSION] ?: return@map null
            UpdateNotice(
                version = version,
                channel = UpdateChannel.fromName(prefs[KEY_NOTICE_CHANNEL]),
                atMillis = prefs[KEY_NOTICE_AT] ?: return@map null,
                installedBuild = prefs[KEY_NOTICE_INSTALLED].orEmpty(),
            )
        }

    suspend fun recordUpdateNotice(notice: UpdateNotice) {
        dataStore.edit { prefs ->
            prefs[KEY_NOTICE_VERSION] = notice.version
            prefs[KEY_NOTICE_CHANNEL] = notice.channel.name
            prefs[KEY_NOTICE_AT] = notice.atMillis
            prefs[KEY_NOTICE_INSTALLED] = notice.installedBuild
        }
    }

    suspend fun clearUpdateNotice() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_NOTICE_VERSION)
            prefs.remove(KEY_NOTICE_CHANNEL)
            prefs.remove(KEY_NOTICE_AT)
            prefs.remove(KEY_NOTICE_INSTALLED)
        }
    }

    /** Use the wallpaper's Material You colours instead of Stricknani's own palette. */
    val dynamicColor = dataStore.data.map { prefs -> prefs[KEY_DYNAMIC_COLOR] ?: false }

    suspend fun setDynamicColor(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_DYNAMIC_COLOR] = enabled }
    }

    /**
     * The print dialog's last choices, so it opens the way it was used. [printContent] is the
     * `PrintContent` name (null = never printed); the UI maps it and falls back to "everything".
     */
    val printContent = dataStore.data.map { prefs -> prefs[KEY_PRINT_CONTENT] }
    val printIncludeImages = dataStore.data.map { prefs -> prefs[KEY_PRINT_IMAGES] ?: true }

    suspend fun setPrintChoices(content: String?, includeImages: Boolean) {
        dataStore.edit { prefs ->
            if (content != null) prefs[KEY_PRINT_CONTENT] = content
            prefs[KEY_PRINT_IMAGES] = includeImages
        }
    }

    /**
     * The reading text size step of the project and yarn detail screens (0 = 90%, 1 = 100%, 2 =
     * 115%), see `ReadingTextSize`.
     */
    val readingTextStep = dataStore.data.map { prefs -> prefs[KEY_READING_TEXT_STEP] ?: 1 }

    suspend fun setReadingTextStep(step: Int) {
        dataStore.edit { prefs -> prefs[KEY_READING_TEXT_STEP] = step }
    }

    /** The movable notes note: its edge ("left"/"right", null = never moved) and height 0..1. */
    val notesFabPlacement =
        dataStore.data.map { prefs -> prefs[KEY_NOTES_FAB_SIDE] to prefs[KEY_NOTES_FAB_Y] }

    suspend fun setNotesFabPlacement(side: String, yFraction: Float) {
        dataStore.edit { prefs ->
            prefs[KEY_NOTES_FAB_SIDE] = side
            prefs[KEY_NOTES_FAB_Y] = yFraction
        }
    }

    /**
     * This device's "Send error reports" switch (on by default; see
     * [ERROR_REPORTING_DEFAULT_ENABLED]). Reporting also needs the server-side choice to be on.
     */
    val errorReportingEnabled =
        dataStore.data.map { prefs ->
            prefs[KEY_ERROR_REPORTING_ENABLED] ?: ERROR_REPORTING_DEFAULT_ENABLED
        }

    suspend fun setErrorReportingEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_ERROR_REPORTING_ENABLED] = enabled }
    }

    /** The last `error_reporting` the server reported (null until a server answered). */
    val errorReportingServer =
        dataStore.data.map { prefs ->
            prefs[KEY_ERROR_REPORTING_SERVER]?.let { raw ->
                runCatching { json.decodeFromString<ErrorReportingDto>(raw) }.getOrNull()
            }
        }

    suspend fun setErrorReportingServer(value: ErrorReportingDto) {
        dataStore.edit { prefs -> prefs[KEY_ERROR_REPORTING_SERVER] = json.encodeToString(value) }
    }

    private companion object {
        /** Debug builds start in developer mode; the stored choice wins when there is one. */
        val DEVELOPER_MODE_DEFAULT = BuildConfig.DEBUG_EXTRAS

        val KEY_ERROR_REPORTING_ENABLED = booleanPreferencesKey("error_reporting_enabled")
        val KEY_ERROR_REPORTING_SERVER = stringPreferencesKey("error_reporting_server")
        val KEY_NOTES_FAB_SIDE = stringPreferencesKey("notes_fab_side")
        val KEY_NOTES_FAB_Y = floatPreferencesKey("notes_fab_y")
        val KEY_READING_TEXT_STEP = intPreferencesKey("reading_text_step")
        val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val KEY_PRINT_CONTENT = stringPreferencesKey("print_content")
        val KEY_PRINT_IMAGES = booleanPreferencesKey("print_include_images")
        val KEY_DISMISSED_UPDATE = stringPreferencesKey("dismissed_update_version")
        val KEY_UPDATE_CHANNEL = stringPreferencesKey("update_channel")
        val KEY_AUTO_UPDATE = booleanPreferencesKey("auto_update")
        val KEY_NOTIFY_UPDATES = booleanPreferencesKey("notify_app_updates")
        val KEY_PROJECT_TIMERS_ENABLED = booleanPreferencesKey("project_timers_enabled")
        val KEY_NOTICE_VERSION = stringPreferencesKey("update_notice_version")
        val KEY_NOTICE_CHANNEL = stringPreferencesKey("update_notice_channel")
        val KEY_NOTICE_AT = longPreferencesKey("update_notice_at")
        val KEY_NOTICE_INSTALLED = stringPreferencesKey("update_notice_installed")
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
