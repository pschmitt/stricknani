package blue.anika.wolle.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blue.anika.wolle.data.db.entity.ProjectEntity
import blue.anika.wolle.data.db.entity.YarnEntity
import blue.anika.wolle.data.media.MediaUrlResolver
import blue.anika.wolle.data.repository.CategoryRepository
import blue.anika.wolle.data.repository.ProjectRepository
import blue.anika.wolle.data.repository.YarnRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val SECTION_LIMIT = 10

/** No "recently viewed" tracking exists yet (would need a view-log table) - "recent" here is the
 * same most-recently-updated ordering `ProjectDao`/`YarnDao` already sort by, a reasonable stand-in
 * until per-user view history is worth adding. */
@HiltViewModel
class HomeViewModel
@Inject
constructor(
    private val projectRepository: ProjectRepository,
    private val yarnRepository: YarnRepository,
    private val categoryRepository: CategoryRepository,
    private val mediaUrlResolver: MediaUrlResolver,
) : ViewModel() {

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    val favoriteProjects: StateFlow<List<ProjectEntity>> =
        projectRepository
            .observeAll()
            .map { it.filter { project -> project.isFavorite }.take(SECTION_LIMIT) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    val favoriteYarns: StateFlow<List<YarnEntity>> =
        yarnRepository
            .observeAll()
            .map { it.filter { yarn -> yarn.isFavorite }.take(SECTION_LIMIT) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    val recentProjects: StateFlow<List<ProjectEntity>> =
        projectRepository
            .observeAll()
            .map { it.take(SECTION_LIMIT) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    init {
        refresh()
    }

    fun previewUrl(path: String?): String? = mediaUrlResolver.resolve(path)

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                categoryRepository.sync()
                projectRepository.sync()
                yarnRepository.sync()
            } catch (e: Exception) {
                _errorMessage.value = "Couldn't sync - showing cached data."
            } finally {
                _isRefreshing.value = false
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
