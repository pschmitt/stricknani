package blue.anika.wolle.ui.projects

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import blue.anika.wolle.R
import blue.anika.wolle.data.api.dto.ProjectDto
import blue.anika.wolle.data.db.entity.ProjectEntity
import blue.anika.wolle.data.media.MediaUrlResolver
import blue.anika.wolle.data.repository.ProjectRepository
import blue.anika.wolle.data.repository.YarnRepository
import blue.anika.wolle.sync.SyncScheduler
import blue.anika.wolle.ui.common.MutationFeedback
import blue.anika.wolle.ui.common.RefreshController
import blue.anika.wolle.ui.common.isUserInitiatedRefresh
import blue.anika.wolle.ui.common.RefreshState
import blue.anika.wolle.ui.common.isOfflineFailure
import blue.anika.wolle.ui.navigation.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LinkedYarn(val id: Int, val name: String, val previewUrl: String?)

sealed interface ProjectDetailUiState {
    data object Loading : ProjectDetailUiState

    data object NotFound : ProjectDetailUiState

    data class Loaded(
        val entity: ProjectEntity,
        val detail: ProjectDto,
        val linkedYarns: List<LinkedYarn>,
    ) : ProjectDetailUiState
}

@HiltViewModel
class ProjectDetailViewModel
@Inject
constructor(
    savedStateHandle: SavedStateHandle,
    private val projectRepository: ProjectRepository,
    private val yarnRepository: YarnRepository,
    private val mediaUrlResolver: MediaUrlResolver,
    private val syncScheduler: SyncScheduler,
    private val mutationFeedback: MutationFeedback,
) : ViewModel() {

    private val projectId = savedStateHandle.toRoute<Route.ProjectDetail>().projectId

    private val _deleted = MutableStateFlow(false)
    val deleted: StateFlow<Boolean> = _deleted.asStateFlow()

    private val refreshController = RefreshController(viewModelScope)
    val refreshState: StateFlow<RefreshState> = refreshController.state
    val isRefreshing: StateFlow<Boolean> =
        refreshState
            .map { it.isUserInitiatedRefresh() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), false)

    // SNA-36: decoding the full detail JSON (steps/images/attachments) is real work, so it's kept
    // in its own upstream step gated by distinctUntilChanged - Room re-emits observeById on *any*
    // write to the projects table (not just this row), and combine's downstream re-runs on every
    // unrelated yarn change too; without this, viewing a project's detail screen would re-decode
    // its JSON on every background sync tick and every yarn edit anywhere else in the app.
    private val detailFlow: Flow<Pair<ProjectEntity, ProjectDto>?> =
        projectRepository.observeById(projectId).distinctUntilChanged().map { entity ->
            entity?.let { it to projectRepository.decodeDetail(it) }
        }

    val uiState: StateFlow<ProjectDetailUiState> =
        combine(detailFlow, yarnRepository.observeAll()) { detailPair, yarns ->
                if (detailPair == null) {
                    ProjectDetailUiState.NotFound
                } else {
                    val (entity, detail) = detailPair
                    val linked =
                        yarns
                            .filter { it.id in detail.yarnIds }
                            .map { LinkedYarn(it.id, it.name, it.previewUrl) }
                    ProjectDetailUiState.Loaded(entity, detail, linked)
                }
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                ProjectDetailUiState.Loading,
            )

    fun resolveMediaUrl(path: String?): String? = mediaUrlResolver.resolve(path)

    /** Refreshes this project and only the yarns currently linked from its latest detail. */
    fun refresh() {
        refreshController.refresh {
            val project = projectRepository.refreshOne(projectId)
            var changed = project.changed
            project.detail.yarnIds.distinct().forEach { yarnId ->
                changed = yarnRepository.refreshOne(yarnId).changed || changed
            }
            changed
        }
    }

    /** The web URL for this project (SNA-17), for the detail screen's share action. */
    fun shareUrl(): String? = mediaUrlResolver.resolve("/projects/$projectId")

    fun toggleFavorite() {
        val state = uiState.value
        if (state !is ProjectDetailUiState.Loaded) return
        val wasFavorite = state.entity.isFavorite
        viewModelScope.launch {
            try {
                projectRepository.toggleFavorite(state.entity, wasFavorite = wasFavorite)
                mutationFeedback.show(
                    if (wasFavorite) R.string.mutation_favorite_removed
                    else R.string.mutation_favorite_added
                )
            } catch (e: Exception) {
                mutationFeedback.show(
                    if (e.isOfflineFailure()) R.string.mutation_favorite_offline
                    else R.string.error_favorite_failed
                )
            }
        }
    }

    fun delete() {
        if (uiState.value !is ProjectDetailUiState.Loaded) return
        viewModelScope.launch {
            try {
                projectRepository.deleteProject(projectId)
                syncScheduler.replayThenSyncNow()
                mutationFeedback.show(R.string.mutation_project_deleted_queued)
                _deleted.value = true
            } catch (e: Exception) {
                mutationFeedback.show(R.string.error_delete_failed)
            }
        }
    }

    fun deleteAttachment(attachmentId: Int) {
        if (uiState.value !is ProjectDetailUiState.Loaded) return
        viewModelScope.launch {
            try {
                projectRepository.queueAttachmentDelete(projectId, attachmentId)
                syncScheduler.replayThenSyncNow()
                mutationFeedback.show(R.string.mutation_project_attachment_deleted_queued)
            } catch (e: Exception) {
                mutationFeedback.show(R.string.error_attachment_delete_failed)
            }
        }
    }

    fun dismissRefreshFeedback() = refreshController.clearFeedback()

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
