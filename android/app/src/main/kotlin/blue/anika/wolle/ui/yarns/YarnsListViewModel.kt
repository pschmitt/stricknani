package blue.anika.wolle.ui.yarns

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blue.anika.wolle.data.db.entity.YarnEntity
import blue.anika.wolle.data.media.MediaUrlResolver
import blue.anika.wolle.data.repository.YarnRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class YarnsListViewModel
@Inject
constructor(private val yarnRepository: YarnRepository, private val mediaUrlResolver: MediaUrlResolver) :
    ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _favoritesOnly = MutableStateFlow(false)
    val favoritesOnly: StateFlow<Boolean> = _favoritesOnly.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    val yarns: StateFlow<List<YarnEntity>> =
        combine(yarnRepository.observeAll(), searchQuery, favoritesOnly) { entities, query, favoritesOnly ->
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
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

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
                _errorMessage.value = "Couldn't sync - showing cached data."
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
