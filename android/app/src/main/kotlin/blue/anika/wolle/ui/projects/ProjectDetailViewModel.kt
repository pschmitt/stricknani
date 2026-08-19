package blue.anika.wolle.ui.projects

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import blue.anika.wolle.data.api.dto.ProjectDto
import blue.anika.wolle.data.db.entity.ProjectEntity
import blue.anika.wolle.data.media.MediaUrlResolver
import blue.anika.wolle.data.repository.ProjectRepository
import blue.anika.wolle.data.repository.YarnRepository
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
) : ViewModel() {

    private val projectId = savedStateHandle.toRoute<Route.ProjectDetail>().projectId

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // SNA-36: decoding the full detail JSON (steps/images/attachments) is real work, so it's kept
    // in its own upstream step gated by distinctUntilChanged - Room re-emits observeById on *any*
    // write to the projects table (not just this row), and combine's downstream re-runs on every
    // unrelated yarn change too; without this, viewing a project's detail screen would re-decode
    // its JSON on every background sync tick and every yarn edit anywhere else in the app.
    private val detailFlow: Flow<Pair<ProjectEntity, ProjectDto>?> =
        projectRepository
            .observeById(projectId)
            .distinctUntilChanged()
            .map { entity -> entity?.let { it to projectRepository.decodeDetail(it) } }

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

    /** The web URL for this project (SNA-17), for the detail screen's share action. */
    fun shareUrl(): String? = mediaUrlResolver.resolve("/projects/$projectId")

    fun toggleFavorite() {
        val state = uiState.value
        if (state !is ProjectDetailUiState.Loaded) return
        viewModelScope.launch {
            try {
                projectRepository.toggleFavorite(
                    state.entity,
                    wasFavorite = state.entity.isFavorite,
                )
            } catch (e: Exception) {
                _errorMessage.value = "Couldn't update favorite - try again."
            }
        }
    }

    fun dismissError() {
        _errorMessage.value = null
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
