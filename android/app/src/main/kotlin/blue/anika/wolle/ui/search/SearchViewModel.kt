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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn

sealed interface SearchResult {
    data class ProjectResult(val entity: ProjectEntity) : SearchResult

    data class YarnResult(val entity: YarnEntity) : SearchResult
}

/**
 * Searches only what's already synced into Room - no network call, so it works fully offline,
 * unlike a server-side search endpoint would.
 */
@OptIn(FlowPreview::class)
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

    // SNA-36: the text field itself (`query` above) updates every keystroke for instant visual
    // feedback, but debouncing the copy driving the actual full-list filter avoids re-scanning
    // every project/yarn on each character typed - only the settled-on query triggers a filter.
    private val debouncedQuery = query.debounce(SEARCH_DEBOUNCE_MILLIS).distinctUntilChanged()

    val results: StateFlow<List<SearchResult>> =
        combine(projectRepository.observeAll(), yarnRepository.observeAll(), debouncedQuery) {
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
        const val SEARCH_DEBOUNCE_MILLIS = 250L
    }
}
