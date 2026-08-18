package blue.anika.wolle.ui.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blue.anika.wolle.data.api.CategoriesApi
import blue.anika.wolle.data.api.dto.CategoryCreateRequest
import blue.anika.wolle.data.db.entity.CategoryEntity
import blue.anika.wolle.data.db.entity.ProjectEntity
import blue.anika.wolle.data.media.MediaUrlResolver
import blue.anika.wolle.data.repository.CategoryRepository
import blue.anika.wolle.data.repository.ProjectRepository
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
class ProjectsListViewModel
@Inject
constructor(
    private val projectRepository: ProjectRepository,
    private val categoryRepository: CategoryRepository,
    private val categoriesApi: CategoriesApi,
    private val mediaUrlResolver: MediaUrlResolver,
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedCategory = MutableStateFlow<String?>(null)
    val selectedCategory: StateFlow<String?> = _selectedCategory.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    val categories: StateFlow<List<CategoryEntity>> =
        categoryRepository
            .observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    val projects: StateFlow<List<ProjectEntity>> =
        combine(projectRepository.observeAll(), searchQuery, selectedCategory) { entities, query, category ->
                entities
                    .asSequence()
                    .filter { category == null || it.category == category }
                    .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
                    .toList()
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    init {
        refresh()
    }

    fun previewUrl(entity: ProjectEntity): String? = mediaUrlResolver.resolve(entity.previewUrl)

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    fun onCategorySelected(category: String?) {
        _selectedCategory.value = category
    }

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                categoryRepository.sync()
                projectRepository.sync()
            } catch (e: Exception) {
                _errorMessage.value = "Couldn't sync - showing cached data."
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun toggleFavorite(entity: ProjectEntity) {
        viewModelScope.launch {
            try {
                projectRepository.toggleFavorite(entity, wasFavorite = entity.isFavorite)
            } catch (e: Exception) {
                _errorMessage.value = "Couldn't update favorite - try again."
            }
        }
    }

    fun addCategory(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            try {
                categoriesApi.createCategory(CategoryCreateRequest(name.trim()))
                categoryRepository.sync()
            } catch (e: Exception) {
                _errorMessage.value = "Couldn't create category '$name'."
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
