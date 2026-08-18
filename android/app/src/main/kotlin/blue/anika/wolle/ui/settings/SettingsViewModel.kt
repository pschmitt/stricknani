package blue.anika.wolle.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blue.anika.wolle.data.api.MetaApi
import blue.anika.wolle.data.api.dto.MetaDto
import blue.anika.wolle.data.db.AppDatabase
import blue.anika.wolle.data.db.dao.SyncStateDao
import blue.anika.wolle.data.settings.AppPreferencesRepository
import blue.anika.wolle.data.settings.SettingsRepository
import blue.anika.wolle.data.settings.ThemeMode
import blue.anika.wolle.data.util.DateTimeUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class SettingsViewModel
@Inject
constructor(
    private val settingsRepository: SettingsRepository,
    private val appPreferencesRepository: AppPreferencesRepository,
    private val database: AppDatabase,
    syncStateDao: SyncStateDao,
    private val metaApi: MetaApi,
) : ViewModel() {

    val serverUrl: StateFlow<String> =
        settingsRepository.credentials
            .map { it.serverUrl }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), "")

    val themeMode: StateFlow<ThemeMode> =
        appPreferencesRepository.themeMode.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            ThemeMode.SYSTEM,
        )

    /** Most recent of the per-entity-type sync cursors (SNA-7's `SyncStateEntity`), or null if
     * nothing has synced yet. */
    val lastSyncedMillis: StateFlow<Long?> =
        syncStateDao
            .observeAll()
            .map { states ->
                states.maxOfOrNull { DateTimeUtils.parseToEpochMillis(it.cursor) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    private val _serverMeta = MutableStateFlow<MetaDto?>(null)
    val serverMeta: StateFlow<MetaDto?> = _serverMeta.asStateFlow()

    init {
        viewModelScope.launch {
            _serverMeta.value = runCatching { metaApi.getMeta() }.getOrNull()
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { appPreferencesRepository.setThemeMode(mode) }
    }

    /**
     * Wipes credentials, which flips `SettingsRepository.isConfigured` to false - `MainActivity`
     * observes that and swaps to the Onboarding-rooted nav graph, so no navigation call is needed
     * here. The Room cache is a rebuildable server-side mirror (not user data), so it's cleared too
     * rather than left to leak the previous account's cached projects/yarns into a fresh sign-in.
     */
    fun signOut() {
        settingsRepository.signOut()
        viewModelScope.launch { withContext(Dispatchers.IO) { database.clearAllTables() } }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
