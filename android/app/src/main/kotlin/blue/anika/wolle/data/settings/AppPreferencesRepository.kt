package blue.anika.wolle.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.map

/**
 * Non-secret, non-sync app preferences (SNA-18's theme toggle) - DataStore rather than
 * `SettingsRepository`'s `EncryptedSharedPreferences`, since nothing here needs Keystore-backed
 * encryption. Sync bookkeeping (SNA-7) stays in Room instead - see `SyncStateEntity`'s kdoc.
 */
@Singleton
class AppPreferencesRepository @Inject constructor(private val dataStore: DataStore<Preferences>) {

    val themeMode =
        dataStore.data.map { prefs ->
            prefs[KEY_THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM
        }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { prefs -> prefs[KEY_THEME_MODE] = mode.name }
    }

    private companion object {
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
    }
}
