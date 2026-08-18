package blue.anika.wolle.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blue.anika.wolle.data.api.MetaApi
import blue.anika.wolle.data.api.dto.MetaDto
import blue.anika.wolle.data.backup.BackupFrequency
import blue.anika.wolle.data.backup.BackupManager
import blue.anika.wolle.data.backup.BackupPasswordRequiredException
import blue.anika.wolle.data.backup.BackupScheduler
import blue.anika.wolle.data.db.AppDatabase
import blue.anika.wolle.data.db.dao.SyncStateDao
import blue.anika.wolle.data.settings.AppPreferencesRepository
import blue.anika.wolle.data.settings.NavbarItemPreference
import blue.anika.wolle.data.settings.SettingsRepository
import blue.anika.wolle.data.settings.ThemeMode
import blue.anika.wolle.data.util.DateTimeUtils
import blue.anika.wolle.sync.SyncScheduler
import blue.anika.wolle.ui.navigation.NavbarCustomization
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    private val backupManager: BackupManager,
    private val backupScheduler: BackupScheduler,
    private val syncScheduler: SyncScheduler,
    @ApplicationContext private val context: Context,
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

    /**
     * Most recent of the per-entity-type sync cursors (SNA-7's `SyncStateEntity`), or null if
     * nothing has synced yet.
     */
    val lastSyncedMillis: StateFlow<Long?> =
        syncStateDao
            .observeAll()
            .map { states -> states.maxOfOrNull { DateTimeUtils.parseToEpochMillis(it.cursor) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    private val _serverMeta = MutableStateFlow<MetaDto?>(null)
    val serverMeta: StateFlow<MetaDto?> = _serverMeta.asStateFlow()

    init {
        viewModelScope.launch { _serverMeta.value = runCatching { metaApi.getMeta() }.getOrNull() }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { appPreferencesRepository.setThemeMode(mode) }
    }

    // --- SNA-34: developer mode easter egg ------------------------------------------------

    private val developerMode: StateFlow<Boolean> =
        appPreferencesRepository.developerMode.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            false,
        )

    private val _developerModeToast =
        MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val developerModeToast = _developerModeToast.asSharedFlow()

    private val developerModeTapState = DeveloperModeTapState()
    private var developerModeUnlocked = false
    private var developerModeUnlockInProgress = false

    /** Tap target is the "Build" row on the About screen, matching syncwich's easter egg. */
    fun onBuildRowTap() {
        if (developerModeUnlockInProgress) return
        when (
            val action =
                developerModeTapState.onTap(
                    SystemClock.elapsedRealtime(),
                    developerMode.value || developerModeUnlocked,
                )
        ) {
            DeveloperModeTapAction.AlreadyDeveloper ->
                _developerModeToast.tryEmit("You are already a developer")
            is DeveloperModeTapAction.Progress ->
                _developerModeToast.tryEmit("${action.remainingTaps} more taps to become a developer")
            DeveloperModeTapAction.Unlock -> {
                developerModeUnlockInProgress = true
                viewModelScope.launch {
                    var enabled = false
                    try {
                        appPreferencesRepository.setDeveloperMode(true)
                        enabled = true
                        _developerModeToast.tryEmit("Developer mode enabled")
                    } finally {
                        if (enabled) developerModeUnlocked = true
                        developerModeUnlockInProgress = false
                    }
                }
            }
        }
    }

    /** Sanitized (all destinations, in the user's chosen order, each with a visibility flag). */
    val navbarItems: StateFlow<List<NavbarItemPreference>> =
        appPreferencesRepository.navbarItems
            .map { raw -> NavbarCustomization.sanitize(raw) }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                NavbarCustomization.sanitize(null),
            )

    /** Swaps the item at [index] with its neighbor in [delta]'s direction (-1 up, +1 down). */
    fun moveNavbarItem(index: Int, delta: Int) {
        val current = navbarItems.value.toMutableList()
        val target = index + delta
        if (index !in current.indices || target !in current.indices) return
        val moved = current.removeAt(index)
        current.add(target, moved)
        viewModelScope.launch { appPreferencesRepository.setNavbarItems(current) }
    }

    /**
     * [TopLevelDestination.SETTINGS] can't be hidden - see `NavbarCustomization.sanitize`'s kdoc.
     */
    fun setNavbarItemVisible(id: String, visible: Boolean) {
        val updated = navbarItems.value.map { if (it.id == id) it.copy(visible = visible) else it }
        viewModelScope.launch { appPreferencesRepository.setNavbarItems(updated) }
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

    // --- SNA-15: backup/restore ---

    private val _backupState = MutableStateFlow<BackupOperationState>(BackupOperationState.Idle)
    val backupState: StateFlow<BackupOperationState> = _backupState.asStateFlow()

    /**
     * Set when [importBackup] hits [BackupPasswordRequiredException], for
     * [retryImportWithPassword].
     */
    private var pendingRestoreUri: Uri? = null

    val scheduledBackupEnabled: StateFlow<Boolean> =
        appPreferencesRepository.scheduledBackupEnabled.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            false,
        )

    val scheduledBackupFolderUri: StateFlow<String?> =
        appPreferencesRepository.scheduledBackupFolderUri.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            null,
        )

    val scheduledBackupFrequency: StateFlow<BackupFrequency> =
        appPreferencesRepository.scheduledBackupFrequency.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            BackupFrequency.WEEKLY,
        )

    val scheduledBackupPasswordSet: StateFlow<Boolean> =
        settingsRepository.scheduledBackupPassword
            .map { !it.isNullOrBlank() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), false)

    fun exportBackup(uri: Uri, password: String?) {
        viewModelScope.launch {
            _backupState.value = BackupOperationState.InProgress
            _backupState.value =
                try {
                    val bytes = backupManager.export(password?.takeIf { it.isNotBlank() })
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                            ?: error("Could not open the selected file for writing")
                    }
                    BackupOperationState.Success("Backup exported")
                } catch (e: Exception) {
                    BackupOperationState.Error(e.message ?: "Export failed")
                }
        }
    }

    fun importBackup(uri: Uri, password: String? = null) {
        viewModelScope.launch {
            _backupState.value = BackupOperationState.InProgress
            _backupState.value =
                try {
                    val bytes =
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                ?: error("Could not open the selected file for reading")
                        }
                    backupManager.import(bytes, password?.takeIf { it.isNotBlank() })
                    pendingRestoreUri = null
                    syncScheduler.syncNow()
                    BackupOperationState.Success("Backup restored - syncing latest data now")
                } catch (e: BackupPasswordRequiredException) {
                    pendingRestoreUri = uri
                    BackupOperationState.PasswordRequired
                } catch (e: Exception) {
                    BackupOperationState.Error(e.message ?: "Restore failed")
                }
        }
    }

    fun retryImportWithPassword(password: String) {
        pendingRestoreUri?.let { importBackup(it, password) }
    }

    fun dismissBackupState() {
        _backupState.value = BackupOperationState.Idle
    }

    fun enableScheduledBackup(folderUri: Uri) {
        // OpenDocumentTree's grant is otherwise transient - without this, BackupWorker loses
        // access to the folder the next time the app process restarts.
        context.contentResolver.takePersistableUriPermission(
            folderUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        viewModelScope.launch {
            appPreferencesRepository.setScheduledBackupFolderUri(folderUri.toString())
            appPreferencesRepository.setScheduledBackupEnabled(true)
            backupScheduler.schedule(scheduledBackupFrequency.value)
        }
    }

    fun disableScheduledBackup() {
        viewModelScope.launch { appPreferencesRepository.setScheduledBackupEnabled(false) }
        backupScheduler.cancel()
    }

    fun setScheduledBackupFrequency(frequency: BackupFrequency) {
        viewModelScope.launch {
            appPreferencesRepository.setScheduledBackupFrequency(frequency)
            if (scheduledBackupEnabled.value) backupScheduler.schedule(frequency)
        }
    }

    fun setScheduledBackupPassword(password: String?) {
        settingsRepository.setScheduledBackupPassword(password)
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

internal sealed interface DeveloperModeTapAction {
    data object AlreadyDeveloper : DeveloperModeTapAction

    data object Unlock : DeveloperModeTapAction

    data class Progress(val remainingTaps: Int) : DeveloperModeTapAction
}

/** 7 taps within a 2s rolling window unlocks developer mode - matches syncwich's easter egg. */
internal class DeveloperModeTapState(
    private val requiredTaps: Int = 7,
    private val tapWindowMillis: Long = 2_000L,
) {
    private var tapCount = 0
    private var lastTapAt = 0L

    fun onTap(now: Long, developerModeEnabled: Boolean): DeveloperModeTapAction {
        if (developerModeEnabled) return DeveloperModeTapAction.AlreadyDeveloper
        if (now - lastTapAt > tapWindowMillis) tapCount = 0
        lastTapAt = now
        tapCount++
        return if (tapCount >= requiredTaps) {
            tapCount = 0
            DeveloperModeTapAction.Unlock
        } else {
            DeveloperModeTapAction.Progress(requiredTaps - tapCount)
        }
    }
}
