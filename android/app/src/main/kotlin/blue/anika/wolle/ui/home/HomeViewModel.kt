package blue.anika.wolle.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blue.anika.wolle.data.db.dao.PendingMutationDao
import blue.anika.wolle.data.db.dao.SyncStateDao
import blue.anika.wolle.data.db.entity.PendingMutationEntity
import blue.anika.wolle.data.db.entity.ProjectEntity
import blue.anika.wolle.data.db.entity.YarnEntity
import blue.anika.wolle.data.media.MediaUrlResolver
import blue.anika.wolle.data.repository.CategoryRepository
import blue.anika.wolle.data.repository.ProjectRepository
import blue.anika.wolle.data.repository.YarnRepository
import blue.anika.wolle.data.util.DateTimeUtils
import blue.anika.wolle.sync.SyncScheduler
import blue.anika.wolle.ui.common.RefreshController
import blue.anika.wolle.ui.common.isUserInitiatedRefresh
import blue.anika.wolle.ui.common.RefreshState
import blue.anika.wolle.ui.common.RefreshTrigger
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

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
) : ViewModel() {

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
    }
}
