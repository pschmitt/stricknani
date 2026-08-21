package blue.anika.wolle.ui.yarns

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blue.anika.wolle.R
import blue.anika.wolle.data.db.entity.YarnEntity
import blue.anika.wolle.data.media.MediaUrlResolver
import blue.anika.wolle.data.repository.YarnRepository
import blue.anika.wolle.ui.common.MutationFeedback
import blue.anika.wolle.ui.common.RefreshController
import blue.anika.wolle.ui.common.RefreshState
import blue.anika.wolle.ui.common.RefreshTrigger
import blue.anika.wolle.ui.common.isOfflineFailure
import blue.anika.wolle.ui.common.isUserInitiatedRefresh
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
@HiltViewModel
class YarnsListViewModel
@Inject
constructor(
    private val yarnRepository: YarnRepository,
    private val mediaUrlResolver: MediaUrlResolver,
    private val mutationFeedback: MutationFeedback,
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // SNA-36: see ProjectsListViewModel's identical fix.
    private val debouncedSearchQuery =
        searchQuery.debounce(SEARCH_DEBOUNCE_MILLIS).distinctUntilChanged()

    private val _favoritesOnly = MutableStateFlow(false)
    val favoritesOnly: StateFlow<Boolean> = _favoritesOnly.asStateFlow()

    private val refreshController = RefreshController(viewModelScope)
    val refreshState: StateFlow<RefreshState> = refreshController.state
    val isRefreshing: StateFlow<Boolean> =
        refreshState
            .map { it.isUserInitiatedRefresh() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), false)

    val yarns: StateFlow<List<YarnEntity>> =
        combine(yarnRepository.observeAll(), debouncedSearchQuery, favoritesOnly) {
                entities,
                query,
                favoritesOnly ->
                entities
                    .asSequence()
                    .filter { !favoritesOnly || it.isFavorite }
                    .filter {
                        query.isBlank() ||
                            it.name.contains(query, ignoreCase = true) ||
                            it.brand?.contains(query, ignoreCase = true) == true ||
                            it.colorway?.contains(query, ignoreCase = true) == true
                    }
                    .toList()
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                emptyList(),
            )

    init {
        refresh(trigger = RefreshTrigger.Automatic)
    }

    fun previewUrl(entity: YarnEntity): String? = mediaUrlResolver.resolve(entity.previewUrl)

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    fun onFavoritesOnlyChange(favoritesOnly: Boolean) {
        _favoritesOnly.value = favoritesOnly
    }

    fun refresh(trigger: RefreshTrigger = RefreshTrigger.UserInitiated) {
        refreshController.refresh(trigger = trigger) { yarnRepository.sync() }
    }

    fun toggleFavorite(entity: YarnEntity) {
        val wasFavorite = entity.isFavorite
        viewModelScope.launch {
            try {
                yarnRepository.toggleFavorite(entity, wasFavorite = wasFavorite)
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

    fun dismissRefreshFeedback() = refreshController.clearFeedback()

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val SEARCH_DEBOUNCE_MILLIS = 250L
    }
}
