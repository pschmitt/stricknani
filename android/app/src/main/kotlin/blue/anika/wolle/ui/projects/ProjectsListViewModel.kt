package blue.anika.wolle.ui.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blue.anika.wolle.R
import blue.anika.wolle.data.api.CategoriesApi
import blue.anika.wolle.data.api.dto.CategoryCreateRequest
import blue.anika.wolle.data.db.entity.CategoryEntity
import blue.anika.wolle.data.db.entity.ProjectEntity
import blue.anika.wolle.data.media.MediaUrlResolver
import blue.anika.wolle.data.repository.CategoryRepository
import blue.anika.wolle.data.repository.ProjectRepository
import blue.anika.wolle.ui.common.MutationFeedback
import blue.anika.wolle.ui.common.RefreshController
import blue.anika.wolle.ui.common.RefreshState
import blue.anika.wolle.ui.common.RefreshTrigger
import blue.anika.wolle.ui.common.isOfflineFailure
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
class ProjectsListViewModel
@Inject
constructor(
    private val projectRepository: ProjectRepository,
    private val categoryRepository: CategoryRepository,
    private val categoriesApi: CategoriesApi,
    private val mediaUrlResolver: MediaUrlResolver,
    private val mutationFeedback: MutationFeedback,
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // SNA-36: debounce the copy driving the filter, not the text field itself - see
    // SearchViewModel's identical fix for why.
    private val debouncedSearchQuery =
        searchQuery.debounce(SEARCH_DEBOUNCE_MILLIS).distinctUntilChanged()

    private val _selectedCategory = MutableStateFlow<String?>(null)
    val selectedCategory: StateFlow<String?> = _selectedCategory.asStateFlow()

    private val refreshController = RefreshController(viewModelScope)
    val refreshState: StateFlow<RefreshState> = refreshController.state
    val isRefreshing: StateFlow<Boolean> =
        refreshState
            .map { it is RefreshState.Refreshing }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), false)

    val categories: StateFlow<List<CategoryEntity>> =
        categoryRepository
            .observeAll()
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                emptyList(),
            )

    val projects: StateFlow<List<ProjectEntity>> =
        combine(projectRepository.observeAll(), debouncedSearchQuery, selectedCategory) {
                entities,
                query,
                category ->
                entities
                    .asSequence()
                    .filter { category == null || it.category == category }
                    .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
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

    fun previewUrl(entity: ProjectEntity): String? = mediaUrlResolver.resolve(entity.previewUrl)

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    fun onCategorySelected(category: String?) {
        _selectedCategory.value = category
    }

    fun refresh(trigger: RefreshTrigger = RefreshTrigger.UserInitiated) {
        refreshController.refresh(trigger = trigger) {
            categoryRepository.sync()
            projectRepository.sync()
        }
    }

    fun toggleFavorite(entity: ProjectEntity) {
        val wasFavorite = entity.isFavorite
        viewModelScope.launch {
            try {
                projectRepository.toggleFavorite(entity, wasFavorite = wasFavorite)
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

    fun addCategory(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            try {
                categoriesApi.createCategory(CategoryCreateRequest(name.trim()))
                categoryRepository.sync()
                mutationFeedback.show(R.string.mutation_category_created, name.trim())
            } catch (e: Exception) {
                mutationFeedback.show(R.string.error_create_category_failed, name)
            }
        }
    }

    fun dismissRefreshFeedback() = refreshController.clearFeedback()

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val SEARCH_DEBOUNCE_MILLIS = 250L
    }
}
