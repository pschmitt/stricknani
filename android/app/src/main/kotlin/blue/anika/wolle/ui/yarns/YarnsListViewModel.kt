package blue.anika.wolle.ui.yarns

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blue.anika.wolle.R
import blue.anika.wolle.data.db.entity.YarnEntity
import blue.anika.wolle.data.media.MediaUrlResolver
import blue.anika.wolle.data.repository.YarnRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.FlowPreview
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
@HiltViewModel
class YarnsListViewModel
@Inject
constructor(
    private val yarnRepository: YarnRepository,
    private val mediaUrlResolver: MediaUrlResolver,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // SNA-36: see ProjectsListViewModel's identical fix.
    private val debouncedSearchQuery =
        searchQuery.debounce(SEARCH_DEBOUNCE_MILLIS).distinctUntilChanged()

    private val _favoritesOnly = MutableStateFlow(false)
    val favoritesOnly: StateFlow<Boolean> = _favoritesOnly.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

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
        refresh()
    }

    fun previewUrl(entity: YarnEntity): String? = mediaUrlResolver.resolve(entity.previewUrl)

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    fun onFavoritesOnlyChange(favoritesOnly: Boolean) {
        _favoritesOnly.value = favoritesOnly
    }

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                yarnRepository.sync()
            } catch (e: Exception) {
                _errorMessage.value = context.getString(R.string.error_sync_failed)
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun toggleFavorite(entity: YarnEntity) {
        viewModelScope.launch {
            try {
                yarnRepository.toggleFavorite(entity, wasFavorite = entity.isFavorite)
            } catch (e: Exception) {
                _errorMessage.value = context.getString(R.string.error_favorite_failed)
            }
        }
    }

    fun dismissError() {
        _errorMessage.value = null
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val SEARCH_DEBOUNCE_MILLIS = 250L
    }
}
