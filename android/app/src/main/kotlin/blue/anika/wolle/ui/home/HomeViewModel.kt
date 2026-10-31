package blue.anika.wolle.ui.home

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blue.anika.wolle.BuildConfig
import blue.anika.wolle.data.db.dao.PendingMutationDao
import blue.anika.wolle.data.db.dao.SyncStateDao
import blue.anika.wolle.data.db.entity.PendingMutationEntity
import blue.anika.wolle.data.db.entity.ProjectEntity
import blue.anika.wolle.data.db.entity.YarnEntity
import blue.anika.wolle.data.media.MediaUrlResolver
import blue.anika.wolle.data.repository.CategoryRepository
import blue.anika.wolle.data.repository.ProjectRepository
import blue.anika.wolle.data.repository.YarnRepository
import blue.anika.wolle.data.settings.AppPreferencesRepository
import blue.anika.wolle.data.update.AppUpdate
import blue.anika.wolle.data.update.UpdateChecker
import blue.anika.wolle.data.util.DateTimeUtils
import blue.anika.wolle.sync.SyncScheduler
import blue.anika.wolle.ui.common.RefreshController
import blue.anika.wolle.ui.common.RefreshState
import blue.anika.wolle.ui.common.RefreshTrigger
import blue.anika.wolle.ui.common.isUserInitiatedRefresh
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
    syncStateDao: SyncStateDao,
    pendingMutationDao: PendingMutationDao,
    private val syncScheduler: SyncScheduler,
    private val updateChecker: UpdateChecker,
    private val appPreferences: AppPreferencesRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val foundUpdate = MutableStateFlow<AppUpdate?>(null)

    /** A newer GitHub release the user hasn't dismissed, or null. */
    val availableUpdate: StateFlow<AppUpdate?> =
        combine(foundUpdate, appPreferences.dismissedUpdateVersion) { update, dismissed ->
                update?.takeIf { it.version != dismissed }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    private val refreshController = RefreshController(viewModelScope)
    val refreshState: StateFlow<RefreshState> = refreshController.state
    val isRefreshing: StateFlow<Boolean> =
        refreshState
            .map { it.isUserInitiatedRefresh() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), false)

    /** Most recent of the per-entity-type sync cursors, or null if nothing has synced yet. */
    val lastSyncedMillis: StateFlow<Long?> =
        syncStateDao
            .observeAll()
            .map { states -> states.maxOfOrNull { DateTimeUtils.parseToEpochMillis(it.cursor) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

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

    val hasSyncFailures: StateFlow<Boolean> =
        failedMutations
            .map { it.isNotEmpty() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), false)

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
        checkForUpdate()
    }

    private fun checkForUpdate() {
        // Play-installed copies update through the store, not from GitHub APKs.
        if (isInstalledFromPlayStore()) return
        viewModelScope.launch {
            foundUpdate.value =
                updateChecker.check(
                    gitRevision = BuildConfig.GIT_REVISION,
                    versionName = BuildConfig.VERSION_NAME,
                    isDebugBuild = BuildConfig.DEBUG,
                    supportedAbis = Build.SUPPORTED_ABIS.toList(),
                )
        }
    }

    @Suppress("DEPRECATION")
    private fun isInstalledFromPlayStore(): Boolean {
        val installer =
            runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        context.packageManager
                            .getInstallSourceInfo(context.packageName)
                            .installingPackageName
                    } else {
                        context.packageManager.getInstallerPackageName(context.packageName)
                    }
                }
                .getOrNull()
        return installer == PLAY_STORE_PACKAGE
    }

    fun dismissUpdate() {
        val version = foundUpdate.value?.version ?: return
        viewModelScope.launch { appPreferences.dismissUpdate(version) }
    }

    fun previewUrl(path: String?): String? = mediaUrlResolver.resolve(path)

    fun refresh(trigger: RefreshTrigger = RefreshTrigger.UserInitiated) {
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
        const val PLAY_STORE_PACKAGE = "com.android.vending"
    }
}
