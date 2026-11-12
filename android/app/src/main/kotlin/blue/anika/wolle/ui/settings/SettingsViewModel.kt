package blue.anika.wolle.ui.settings

import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import android.os.SystemClock
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blue.anika.wolle.BuildConfig
import blue.anika.wolle.R
import blue.anika.wolle.data.api.ApiCapabilities
import blue.anika.wolle.data.api.ApiCapabilitiesState
import blue.anika.wolle.data.api.v2.MetaV2Api
import blue.anika.wolle.data.api.v2.MetaV2Dto
import blue.anika.wolle.data.backup.BackupFrequency
import blue.anika.wolle.data.backup.BackupManager
import blue.anika.wolle.data.backup.BackupPasswordRequiredException
import blue.anika.wolle.data.backup.BackupScheduler
import blue.anika.wolle.data.db.AppDatabase
import blue.anika.wolle.data.db.dao.PendingMutationDao
import blue.anika.wolle.data.db.dao.SyncStateDao
import blue.anika.wolle.data.db.entity.PendingMutationEntity
import blue.anika.wolle.data.export.LocalMoveCoordinator
import blue.anika.wolle.data.impersonation.ImpersonationController
import blue.anika.wolle.data.impersonation.SessionMode
import blue.anika.wolle.data.repository.EVERYONE_FILTER_KEY
import blue.anika.wolle.data.settings.AppLanguage
import blue.anika.wolle.data.settings.AppPreferencesRepository
import blue.anika.wolle.data.settings.NavbarItemPreference
import blue.anika.wolle.data.settings.SettingsRepository
import blue.anika.wolle.data.settings.ThemeMode
import blue.anika.wolle.data.timer.ProjectTimerReminderScheduler
import blue.anika.wolle.data.timer.RunningTimers
import blue.anika.wolle.data.update.UpdateChannel
import blue.anika.wolle.data.update.UpdateCheckResult
import blue.anika.wolle.data.update.UpdateChecker
import blue.anika.wolle.data.update.checkCurrentBuild
import blue.anika.wolle.data.util.DateTimeUtils
import blue.anika.wolle.sync.SyncScheduler
import blue.anika.wolle.ui.common.ReadingTextSize
import blue.anika.wolle.ui.common.RefreshController
import blue.anika.wolle.ui.common.isUserInitiatedRefresh
import blue.anika.wolle.ui.navigation.NavbarCustomization
import coil3.SingletonImageLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** State of the export that "Use a server instead" runs before it signs out. */
enum class LocalMoveExport {
    Idle,
    Exporting,
    Failed,
}

@HiltViewModel
class SettingsViewModel
@Inject
constructor(
    private val settingsRepository: SettingsRepository,
    private val appPreferencesRepository: AppPreferencesRepository,
    private val database: AppDatabase,
    syncStateDao: SyncStateDao,
    private val pendingMutationDao: PendingMutationDao,
    private val metaApi: MetaV2Api,
    private val backupManager: BackupManager,
    private val backupScheduler: BackupScheduler,
    private val syncScheduler: SyncScheduler,
    private val capabilities: ApiCapabilities,
    private val updateChecker: UpdateChecker,
    private val timerReminderScheduler: ProjectTimerReminderScheduler,
    private val runningTimers: RunningTimers,
    private val localMove: LocalMoveCoordinator,
    private val impersonation: ImpersonationController,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _moveExport = MutableStateFlow(LocalMoveExport.Idle)

    /** The "bring my data along" export of "Use a server instead" (T130.2). */
    val moveExport: StateFlow<LocalMoveExport> = _moveExport.asStateFlow()

    /**
     * "Use a server instead". With [bringData] the local data is exported into a private file first
     * and the sign-out only follows a successful export; a failed one keeps the user in local mode.
     */
    fun useServer(bringData: Boolean) {
        if (_moveExport.value == LocalMoveExport.Exporting) return
        if (!bringData) {
            localMove.discard()
            signOut()
            return
        }
        _moveExport.value = LocalMoveExport.Exporting
        viewModelScope.launch {
            if (localMove.prepare()) {
                _moveExport.value = LocalMoveExport.Idle
                signOut()
            } else {
                _moveExport.value = LocalMoveExport.Failed
            }
        }
    }

    fun dismissMoveFailure() {
        _moveExport.value = LocalMoveExport.Idle
    }

    /** Local-only mode (T130): the server runs inside the app and there is one local account. */
    val isLocalMode: Boolean
        get() = settingsRepository.isLocalMode

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

    val projectTimersEnabled: StateFlow<Boolean> =
        appPreferencesRepository.projectTimersEnabled.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            true,
        )

    fun setProjectTimersEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appPreferencesRepository.setProjectTimersEnabled(enabled)
            if (!enabled) {
                timerReminderScheduler.cancelAll()
                runningTimers.clearAll()
            }
        }
    }

    /**
     * Most recent of the per-entity-type sync cursors (SNA-7's `SyncStateEntity`), or null if
     * nothing has synced yet.
     */
    val lastSyncedMillis: StateFlow<Long?> =
        syncStateDao
            .observeAll()
            .map { states ->
                states
                    .filter { it.entityType != EVERYONE_FILTER_KEY }
                    .maxOfOrNull { DateTimeUtils.parseToEpochMillis(it.cursor) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    val failedMutations: StateFlow<List<PendingMutationEntity>> =
        pendingMutationDao
            .observeFailed()
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                emptyList(),
            )

    private val _serverMeta = MutableStateFlow<MetaV2Dto?>(null)
    val serverMeta: StateFlow<MetaV2Dto?> = _serverMeta.asStateFlow()

    private val updateResult = MutableStateFlow<UpdateCheckResult?>(null)
    private val updateChecking = MutableStateFlow(BuildConfig.SELF_UPDATE_ENABLED)

    /** Update row of the About screen; the check starts with the screen and can be repeated. */
    val updateState: StateFlow<AboutUpdateState> =
        combine(updateResult, updateChecking) { result, checking ->
                aboutUpdateState(BuildConfig.SELF_UPDATE_ENABLED, checking, result)
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                aboutUpdateState(BuildConfig.SELF_UPDATE_ENABLED, true, null),
            )

    private var updateCheckJob: Job? = null

    /** Runs a GitHub Releases check unless one is already in flight (or Play handles updates). */
    fun checkForUpdate() {
        startUpdateCheck()
    }

    /** The running check, a new one, or null when Play handles updates. */
    private fun startUpdateCheck(): Job? {
        if (!BuildConfig.SELF_UPDATE_ENABLED) return null
        updateCheckJob
            ?.takeIf { it.isActive }
            ?.let {
                return it
            }
        updateChecking.value = true
        return viewModelScope
            .launch {
                try {
                    updateResult.value =
                        updateChecker.checkCurrentBuild(
                            appPreferencesRepository.updateChannel.first()
                        )
                } finally {
                    updateChecking.value = false
                }
            }
            .also { updateCheckJob = it }
    }

    private val aboutRefresh = RefreshController(viewModelScope)

    /** True while a pull to refresh on the About screen runs; the automatic check never sets it. */
    val aboutRefreshing: StateFlow<Boolean> =
        aboutRefresh.state
            .map { it.isUserInitiatedRefresh() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), false)

    /** Pull to refresh on About: the update check and the server version, both awaited. */
    fun refreshAbout() {
        aboutRefresh.refresh {
            val meta = viewModelScope.async { runCatching { metaApi.getMeta().data }.getOrNull() }
            startUpdateCheck()?.join()
            meta.await()?.let { _serverMeta.value = it }
            true
        }
    }

    /** Release stream of the update check; only offered in builds that can self-update. */
    val updateChannel: StateFlow<UpdateChannel> =
        appPreferencesRepository.updateChannel.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            UpdateChannel.STABLE,
        )

    /** Persists the channel and checks again, so the row answers for the new stream right away. */
    fun setUpdateChannel(channel: UpdateChannel) {
        if (!BuildConfig.SELF_UPDATE_ENABLED) return
        viewModelScope.launch {
            appPreferencesRepository.setUpdateChannel(channel)
            updateCheckJob?.cancelAndJoin()
            updateResult.value = null
            checkForUpdate()
        }
    }

    init {
        viewModelScope.launch {
            _serverMeta.value = runCatching { metaApi.getMeta().data }.getOrNull()
        }
    }

    /** The reading text size step of the project and yarn pages (see `ReadingTextSize`). */
    val readingTextStep: StateFlow<Int> =
        appPreferencesRepository.readingTextStep
            .map { ReadingTextSize.clamp(it) }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                ReadingTextSize.DEFAULT_STEP,
            )

    fun setReadingTextStep(step: Int) {
        viewModelScope.launch {
            appPreferencesRepository.setReadingTextStep(ReadingTextSize.clamp(step))
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { appPreferencesRepository.setThemeMode(mode) }
    }

    val dynamicColor: StateFlow<Boolean> =
        appPreferencesRepository.dynamicColor.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            false,
        )

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { appPreferencesRepository.setDynamicColor(enabled) }
    }

    val syncStatus: StateFlow<SyncWorkStatus> =
        syncScheduler.syncWorkInfos
            .map(::syncWorkStatus)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                SyncWorkStatus.IDLE,
            )

    val pendingSyncCount: StateFlow<Int> =
        pendingMutationDao
            .observeCount()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), 0)

    fun retryFailedMutations() = syncScheduler.replayThenSyncNow()

    /** Removes a conflict notice after the replay worker has already adopted the server version. */
    fun dismissSyncIssue(id: Long) {
        viewModelScope.launch { pendingMutationDao.dismissConflict(id) }
    }

    // --- SNA-37: in-app language picker -----------------------------------------------------

    private val _appLanguage = MutableStateFlow(currentAppLanguage())
    val appLanguage: StateFlow<AppLanguage> = _appLanguage.asStateFlow()

    /**
     * On API 33+, `AppCompatDelegate.setApplicationLocales()` silently no-ops for a plain
     * `ComponentActivity` (its static locale storage only actually reaches the platform
     * `LocaleManager` when an `AppCompatDelegate` has been installed on an `AppCompatActivity`) -
     * verified empirically via `adb shell cmd locale get-app-locales` staying empty after calling
     * it. Call `LocaleManager` directly instead; keep the `AppCompatDelegate` fallback (persisted
     * via the manifest's `AppLocalesMetadataHolderService` auto-store opt-in) for API < 33, where
     * there's no platform `LocaleManager` to call. The caller is still responsible for recreating
     * the activity so already-composed strings actually refresh.
     */
    fun setAppLanguage(language: AppLanguage) {
        _appLanguage.value = language
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales =
                language.tag?.let { LocaleList.forLanguageTags(it) }
                    ?: LocaleList.getEmptyLocaleList()
        } else {
            AppCompatDelegate.setApplicationLocales(
                language.tag?.let { LocaleListCompat.forLanguageTags(it) }
                    ?: LocaleListCompat.getEmptyLocaleList()
            )
        }
    }

    private fun currentAppLanguage(): AppLanguage {
        val tag =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context
                    .getSystemService(LocaleManager::class.java)
                    ?.applicationLocales
                    ?.toLanguageTags()
                    ?.takeIf { it.isNotBlank() }
            } else {
                AppCompatDelegate.getApplicationLocales().toLanguageTags().takeIf {
                    it.isNotBlank()
                }
            }
        return AppLanguage.entries.firstOrNull { it.tag == tag } ?: AppLanguage.SYSTEM
    }

    // --- SNA-34: developer mode easter egg ------------------------------------------------

    val developerMode: StateFlow<Boolean> =
        appPreferencesRepository.developerMode.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            false,
        )

    /** A toast for the About screen; [unlocked] also asks for the celebration (mascot, haptic). */
    data class DeveloperModeEvent(val message: String, val unlocked: Boolean = false)

    private val _developerModeEvents =
        MutableSharedFlow<DeveloperModeEvent>(
            extraBufferCapacity = 4,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val developerModeEvents = _developerModeEvents.asSharedFlow()

    private val developerModeTapState = DeveloperModeTapState()
    private var developerModeUnlocked = false
    private var developerModeUnlockInProgress = false

    /** Tap target is the "Build" row on the About screen. */
    fun onBuildRowTap() {
        if (developerModeUnlockInProgress) return
        when (
            val action =
                developerModeTapState.onTap(
                    SystemClock.elapsedRealtime(),
                    developerMode.value || developerModeUnlocked,
                )
        ) {
            DeveloperModeTapAction.Silent -> Unit
            DeveloperModeTapAction.AlreadyDeveloper ->
                _developerModeEvents.tryEmit(
                    DeveloperModeEvent(context.getString(R.string.settings_dev_mode_already))
                )
            is DeveloperModeTapAction.Countdown ->
                _developerModeEvents.tryEmit(
                    DeveloperModeEvent(
                        context.resources.getQuantityString(
                            R.plurals.settings_dev_mode_steps_remaining,
                            action.stepsLeft,
                            action.stepsLeft,
                        )
                    )
                )
            DeveloperModeTapAction.Unlock -> {
                developerModeUnlockInProgress = true
                viewModelScope.launch {
                    var enabled = false
                    try {
                        appPreferencesRepository.setDeveloperMode(true)
                        enabled = true
                        _developerModeEvents.tryEmit(
                            DeveloperModeEvent(
                                context.getString(R.string.settings_dev_mode_enabled),
                                unlocked = true,
                            )
                        )
                    } finally {
                        if (enabled) developerModeUnlocked = true
                        developerModeUnlockInProgress = false
                    }
                }
            }
        }
    }

    /** The "Developer mode" switch in the Developer card. */
    fun setDeveloperMode(enabled: Boolean) {
        developerModeUnlocked = enabled
        developerModeTapState.reset()
        viewModelScope.launch {
            appPreferencesRepository.setDeveloperMode(enabled)
            if (!enabled) {
                _developerModeEvents.tryEmit(
                    DeveloperModeEvent(context.getString(R.string.settings_dev_mode_turned_off))
                )
            }
        }
    }

    /** Queued writes that have not reached the server yet. */
    val pendingWrites: StateFlow<Int> =
        pendingMutationDao
            .observeCount()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), 0)

    /** The API generation and token id the app resolved for this server; null until known. */
    private val _apiState = MutableStateFlow<ApiCapabilitiesState?>(null)
    val apiState: StateFlow<ApiCapabilitiesState?> = _apiState.asStateFlow()

    init {
        viewModelScope.launch {
            _apiState.value = runCatching { capabilities.resolve() }.getOrNull()
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
        // While acting as another account, signing out means going back to one's own account; the
        // administrator's credentials and data are not touched.
        if (impersonation.isImpersonating) {
            viewModelScope.launch { impersonation.stop() }
            return
        }
        timerReminderScheduler.cancelAll()
        viewModelScope.launch {
            timerReminderScheduler.clearSavedDeadlines()
            runningTimers.clearAll()
        }
        settingsRepository.signOut()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                database.clearAllTables()
                // The queued upload files belong to the account that picked them; their rows are
                // gone now, so the files must not outlive the sign-out either.
                runCatching { File(context.filesDir, SessionMode.uploadsDirectory).deleteRecursively() }
            }
        }
    }

    /**
     * The server deleted this account. Wipes everything the app kept for it - credentials, the Room
     * cache, queued uploads and the image cache - and, like [signOut], flips `isConfigured` so the
     * app returns to onboarding.
     */
    fun accountDeleted() {
        signOut()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { File(context.filesDir, SessionMode.uploadsDirectory).deleteRecursively() }
                runCatching {
                    SingletonImageLoader.get(context).let {
                        it.memoryCache?.clear()
                        it.diskCache?.clear()
                    }
                }
            }
        }
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

    val scheduledBackupIncludeData: StateFlow<Boolean> =
        appPreferencesRepository.scheduledBackupIncludeData.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            false,
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
                            ?: error(context.getString(R.string.backup_error_open_file_write))
                    }
                    BackupOperationState.Success(
                        context.getString(R.string.backup_success_exported)
                    )
                } catch (e: Exception) {
                    BackupOperationState.Error(
                        e.message ?: context.getString(R.string.backup_error_export_failed)
                    )
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
                                ?: error(context.getString(R.string.backup_error_open_file_read))
                        }
                    backupManager.import(bytes, password?.takeIf { it.isNotBlank() })
                    pendingRestoreUri = null
                    syncScheduler.syncNow()
                    BackupOperationState.Success(
                        context.getString(R.string.backup_success_restored)
                    )
                } catch (e: BackupPasswordRequiredException) {
                    pendingRestoreUri = uri
                    BackupOperationState.PasswordRequired
                } catch (e: Exception) {
                    BackupOperationState.Error(
                        e.message ?: context.getString(R.string.backup_error_restore_failed)
                    )
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

    fun setScheduledBackupIncludeData(enabled: Boolean) {
        viewModelScope.launch { appPreferencesRepository.setScheduledBackupIncludeData(enabled) }
    }

    fun setScheduledBackupPassword(password: String?) {
        settingsRepository.setScheduledBackupPassword(password)
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
