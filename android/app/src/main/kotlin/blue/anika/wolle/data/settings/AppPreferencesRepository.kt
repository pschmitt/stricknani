package blue.anika.wolle.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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

    private companion object {
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_NAVBAR_ITEMS = stringPreferencesKey("navbar_items")
    }
}
