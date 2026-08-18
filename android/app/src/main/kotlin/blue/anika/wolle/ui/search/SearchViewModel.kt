package blue.anika.wolle.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blue.anika.wolle.data.db.entity.ProjectEntity
import blue.anika.wolle.data.db.entity.YarnEntity
import blue.anika.wolle.data.media.MediaUrlResolver
import blue.anika.wolle.data.repository.ProjectRepository
import blue.anika.wolle.data.repository.YarnRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

sealed interface SearchResult {
    data class ProjectResult(val entity: ProjectEntity) : SearchResult

    data class YarnResult(val entity: YarnEntity) : SearchResult
}

/**
 * Searches only what's already synced into Room - no network call, so it works fully offline,
 * unlike a server-side search endpoint would.
 */
@HiltViewModel
class SearchViewModel
@Inject
constructor(
    projectRepository: ProjectRepository,
    yarnRepository: YarnRepository,
    private val mediaUrlResolver: MediaUrlResolver,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val results: StateFlow<List<SearchResult>> =
        combine(projectRepository.observeAll(), yarnRepository.observeAll(), query) {
                projects,
                yarns,
                query ->
                if (query.isBlank()) {
                    emptyList()
                } else {
                    val matchedProjects = projects.filter {
                        it.name.contains(query, ignoreCase = true) ||
                            it.category?.contains(query, ignoreCase = true) == true
                    }
                    val matchedYarns = yarns.filter {
                        it.name.contains(query, ignoreCase = true) ||
                            it.brand?.contains(query, ignoreCase = true) == true ||
                            it.colorway?.contains(query, ignoreCase = true) == true
                    }
                    matchedProjects.map { SearchResult.ProjectResult(it) } +
                        matchedYarns.map { SearchResult.YarnResult(it) }
                }
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                emptyList(),
            )

    fun onQueryChange(query: String) {
        _query.value = query
    }

    fun previewUrl(path: String?): String? = mediaUrlResolver.resolve(path)

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
