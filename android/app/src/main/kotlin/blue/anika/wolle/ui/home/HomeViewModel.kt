package blue.anika.wolle.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blue.anika.wolle.BuildConfig
import blue.anika.wolle.data.db.dao.PendingMutationDao
import blue.anika.wolle.data.db.entity.PendingMutationEntity
import blue.anika.wolle.data.db.entity.ProjectEntity
import blue.anika.wolle.data.db.entity.YarnEntity
import blue.anika.wolle.data.export.LocalMoveCoordinator
import blue.anika.wolle.data.export.LocalMoveOffer
import blue.anika.wolle.data.media.MediaUrlResolver
import blue.anika.wolle.data.repository.ActivityRepository
import blue.anika.wolle.data.repository.CategoryRepository
import blue.anika.wolle.data.repository.ProjectRepository
import blue.anika.wolle.data.repository.YarnRepository
import blue.anika.wolle.data.settings.AppPreferencesRepository
import blue.anika.wolle.data.settings.SettingsRepository
import blue.anika.wolle.data.update.AppUpdate
import blue.anika.wolle.data.update.UpdateCheckResult
import blue.anika.wolle.data.update.UpdateChecker
import blue.anika.wolle.data.update.checkCurrentBuild
import blue.anika.wolle.sync.SyncScheduler
import blue.anika.wolle.ui.common.RefreshController
import blue.anika.wolle.ui.common.RefreshState
import blue.anika.wolle.ui.common.RefreshTrigger
import blue.anika.wolle.ui.common.isUserInitiatedRefresh
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val SECTION_LIMIT = 10

/**
 * No "recently viewed" tracking exists yet (would need a view-log table) - "recent" here is the
 * same most-recently-updated ordering `ProjectDao`/`YarnDao` already sort by, a reasonable stand-in
 * until per-user view history is worth adding.
 */
@HiltViewModel
class HomeViewModel
@Inject
constructor(
    private val projectRepository: ProjectRepository,
    private val yarnRepository: YarnRepository,
    private val categoryRepository: CategoryRepository,
    private val mediaUrlResolver: MediaUrlResolver,
    pendingMutationDao: PendingMutationDao,
    private val syncScheduler: SyncScheduler,
    private val updateChecker: UpdateChecker,
    private val appPreferences: AppPreferencesRepository,
    private val settingsRepository: SettingsRepository,
    activityRepository: ActivityRepository,
    private val localMove: LocalMoveCoordinator,
) : ViewModel() {

    /** T130.2: the local data that waits to be uploaded to the server just signed in to. */
    val localMoveOffer: StateFlow<LocalMoveOffer?> = localMove.offer

    fun uploadLocalData(offer: LocalMoveOffer.Ask) {
        localMove.upload(offer.credentials)
    }

    fun localDataNotNow(offer: LocalMoveOffer.Ask) = localMove.notNow(offer.credentials)

    fun discardLocalData() = localMove.discard()

    /** Local-only mode: nothing to pull from a server, so the empty state says what to do. */
    val isLocalMode: Boolean
        get() = settingsRepository.isLocalMode

    private val activityController = ActivityController(viewModelScope, activityRepository::page)

    /** Home's online-only "Recent activity" (see [ActivitySection]). */
    val activity: StateFlow<ActivityUiState> = activityController.state

    private var lastSignedOut: Boolean? = null

    /**
     * The feed needs a session: clear it on sign-out, and load it again when the user comes back.
     * The first report only records the state, [refresh] already loads on start.
     */
    fun onSignedOutChanged(signedOut: Boolean) {
        val previous = lastSignedOut
        lastSignedOut = signedOut
        when {
            signedOut -> activityController.hide()
            previous == true -> activityController.reload()
        }
    }

    private val foundUpdate = MutableStateFlow<AppUpdate?>(null)

    /** A newer GitHub release the user hasn't dismissed, or null. */
    val availableUpdate: StateFlow<AppUpdate?> =
        combine(foundUpdate, appPreferences.dismissedUpdateVersion) { update, dismissed ->
                update?.takeIf { it.version != dismissed }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    private val refreshController =
        RefreshController(
            viewModelScope,
            skipAutomatic = { settingsRepository.sessionExpired.value },
        )
    val refreshState: StateFlow<RefreshState> = refreshController.state
    val isRefreshing: StateFlow<Boolean> =
        refreshState
            .map { it.isUserInitiatedRefresh() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), false)

    /** Local edits (SNA-8) not yet replayed to the server. */
    val pendingChangesCount: StateFlow<Int> =
        pendingMutationDao
            .observeCount()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), 0)

    val failedMutations: StateFlow<List<PendingMutationEntity>> =
        pendingMutationDao
            .observeFailed()
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                emptyList(),
            )

    val favoriteProjects: StateFlow<List<ProjectEntity>> =
        projectRepository
            .observeAll()
            .map { it.filter { project -> project.isFavorite }.take(SECTION_LIMIT) }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                emptyList(),
            )

    val favoriteYarns: StateFlow<List<YarnEntity>> =
        yarnRepository
            .observeAll()
            .map { it.filter { yarn -> yarn.isFavorite }.take(SECTION_LIMIT) }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                emptyList(),
            )

    val recentProjects: StateFlow<List<ProjectEntity>> =
        projectRepository
            .observeAll()
            .map { it.take(SECTION_LIMIT) }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                emptyList(),
            )

    init {
        refresh(trigger = RefreshTrigger.Automatic)
        if (BuildConfig.SELF_UPDATE_ENABLED) {
            // A channel switch in About replaces the card with the other stream's answer.
            viewModelScope.launch {
                appPreferences.updateChannel.drop(1).collect {
                    updateCheckJob?.cancel()
                    checkForUpdate()
                }
            }
        }
    }

    private var updateCheckJob: Job? = null

    private fun checkForUpdate() {
        // Off in the Play bundle (see app/build.gradle.kts): Play installs update through Play.
        if (!BuildConfig.SELF_UPDATE_ENABLED) return
        if (updateCheckJob?.isActive == true) return
        updateCheckJob = viewModelScope.launch {
            // A failed or offline check keeps any update already found; any definite answer
            // (including "ahead of stable" after a channel switch) replaces it.
            when (
                val result = updateChecker.checkCurrentBuild(appPreferences.updateChannel.first())
            ) {
                is UpdateCheckResult.Available -> foundUpdate.value = result.update
                UpdateCheckResult.Failed -> Unit
                UpdateCheckResult.UpToDate,
                UpdateCheckResult.AheadOfStable,
                UpdateCheckResult.NotEligible -> foundUpdate.value = null
            }
        }
    }

    fun dismissUpdate() {
        val version = foundUpdate.value?.version ?: return
        viewModelScope.launch { appPreferences.dismissUpdate(version) }
    }

    fun previewUrl(path: String?): String? = mediaUrlResolver.resolve(path)

    fun refresh(trigger: RefreshTrigger = RefreshTrigger.UserInitiated) {
        checkForUpdate()
        if (!settingsRepository.sessionExpired.value) activityController.reload()
        refreshController.refresh(trigger = trigger) {
            categoryRepository.sync()
            val projectsChanged = projectRepository.sync()
            val yarnsChanged = yarnRepository.sync()
            projectsChanged || yarnsChanged
        }
    }

    fun dismissRefreshFeedback() = refreshController.clearFeedback()

    fun retryFailedMutations() = syncScheduler.replayThenSyncNow()

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
