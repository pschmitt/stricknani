package blue.anika.wolle.ui.yarns

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import blue.anika.wolle.data.api.dto.YarnDto
import blue.anika.wolle.data.db.entity.YarnEntity
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

data class LinkedProject(val id: Int, val name: String, val previewUrl: String?)

sealed interface YarnDetailUiState {
    data object Loading : YarnDetailUiState

    data object NotFound : YarnDetailUiState

    data class Loaded(
        val entity: YarnEntity,
        val detail: YarnDto,
        val linkedProjects: List<LinkedProject>,
    ) : YarnDetailUiState
}

@HiltViewModel
class YarnDetailViewModel
@Inject
constructor(
    savedStateHandle: SavedStateHandle,
    private val yarnRepository: YarnRepository,
    private val projectRepository: ProjectRepository,
    private val mediaUrlResolver: MediaUrlResolver,
) : ViewModel() {

    private val yarnId = savedStateHandle.toRoute<Route.YarnDetail>().yarnId

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // SNA-36: see `ProjectDetailViewModel`'s identical fix - keeps the JSON detail decode gated on
    // this yarn's own row actually changing, not every unrelated project write.
    private val detailFlow: Flow<Pair<YarnEntity, YarnDto>?> =
        yarnRepository
            .observeById(yarnId)
            .distinctUntilChanged()
            .map { entity -> entity?.let { it to yarnRepository.decodeDetail(it) } }

    val uiState: StateFlow<YarnDetailUiState> =
        combine(detailFlow, projectRepository.observeAll()) { detailPair, projects ->
                if (detailPair == null) {
                    YarnDetailUiState.NotFound
                } else {
                    val (entity, detail) = detailPair
                    val linked =
                        projects
                            .filter { it.id in detail.projectIds }
                            .map { LinkedProject(it.id, it.name, it.previewUrl) }
                    YarnDetailUiState.Loaded(entity, detail, linked)
                }
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                YarnDetailUiState.Loading,
            )

    fun resolveMediaUrl(path: String?): String? = mediaUrlResolver.resolve(path)

    /** The web URL for this yarn (SNA-17), for the detail screen's share action. */
    fun shareUrl(): String? = mediaUrlResolver.resolve("/yarn/$yarnId")

    fun toggleFavorite() {
        val state = uiState.value
        if (state !is YarnDetailUiState.Loaded) return
        viewModelScope.launch {
            try {
                yarnRepository.toggleFavorite(state.entity, wasFavorite = state.entity.isFavorite)
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
