package blue.anika.wolle.ui.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blue.anika.wolle.R
import blue.anika.wolle.data.api.CategoriesApi
import blue.anika.wolle.data.api.dto.CategoryUpdateRequest
import blue.anika.wolle.data.db.entity.CategoryEntity
import blue.anika.wolle.data.repository.CategoryRepository
import blue.anika.wolle.ui.common.MutationFeedback
import blue.anika.wolle.ui.common.RefreshController
import blue.anika.wolle.ui.common.RefreshState
import blue.anika.wolle.ui.common.RefreshTrigger
import blue.anika.wolle.ui.common.isUserInitiatedRefresh
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The content state for the categories destination. */
sealed interface CategoriesContentState {
    data object Loading : CategoriesContentState

    data class Data(val categories: List<CategoryEntity>) : CategoriesContentState

    data object Empty : CategoriesContentState

    data object Offline : CategoriesContentState

    data object Error : CategoriesContentState
}

data class CategoriesUiState(
    val content: CategoriesContentState = CategoriesContentState.Loading,
    val isRefreshing: Boolean = false,
)

/**
 * Selects the visible state for a cache-first categories screen.
 *
 * Existing cached rows stay visible while a refresh runs or fails. Loading/error states are only
 * used when there is no cache to show, so losing connectivity never turns a populated screen into a
 * blank one.
 */
internal fun categoriesContentState(
    categories: List<CategoryEntity>,
    initialSyncComplete: Boolean,
    refreshState: RefreshState,
): CategoriesContentState =
    when {
        categories.isNotEmpty() -> CategoriesContentState.Data(categories)
        !initialSyncComplete && refreshState is RefreshState.Refreshing ->
            CategoriesContentState.Loading
        !initialSyncComplete && refreshState is RefreshState.Offline ->
            CategoriesContentState.Offline
        !initialSyncComplete && refreshState is RefreshState.Error -> CategoriesContentState.Error
        !initialSyncComplete -> CategoriesContentState.Loading
        else -> CategoriesContentState.Empty
    }

@HiltViewModel
class CategoriesViewModel
@Inject
constructor(
    private val categoryRepository: CategoryRepository,
    private val categoriesApi: CategoriesApi,
    private val mutationFeedback: MutationFeedback,
) : ViewModel() {
    private val initialSyncComplete = MutableStateFlow(false)
    private val refreshController = RefreshController(viewModelScope)

    private val categories: StateFlow<List<CategoryEntity>> =
        categoryRepository
            .observeAll()
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                emptyList(),
            )

    val refreshState: StateFlow<RefreshState> = refreshController.state

    val uiState: StateFlow<CategoriesUiState> =
        combine(categories, refreshController.state, initialSyncComplete) {
                cachedCategories,
                refresh,
                hasCompletedInitialSync ->
                CategoriesUiState(
                    content =
                        categoriesContentState(
                            categories = cachedCategories,
                            initialSyncComplete = hasCompletedInitialSync,
                            refreshState = refresh,
                        ),
                    isRefreshing = refresh.isUserInitiatedRefresh(),
                )
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                CategoriesUiState(),
            )

    init {
        refresh(trigger = RefreshTrigger.Automatic)
    }

    fun refresh(trigger: RefreshTrigger = RefreshTrigger.UserInitiated) {
        refreshController.refresh(trigger = trigger) {
            val changed = categoryRepository.sync()
            initialSyncComplete.value = true
            changed
        }
    }

    fun dismissRefreshFeedback() = refreshController.clearFeedback()

    fun renameCategory(category: CategoryEntity, name: String) {
        val cleaned = name.trim()
        if (cleaned.isBlank()) {
            mutationFeedback.show(R.string.error_name_required)
            return
        }
        viewModelScope.launch {
            try {
                categoriesApi.updateCategory(category.id, CategoryUpdateRequest(cleaned))
                categoryRepository.sync()
                mutationFeedback.show(R.string.mutation_category_renamed, category.name, cleaned)
            } catch (_: Exception) {
                mutationFeedback.show(R.string.error_update_category_failed, category.name)
            }
        }
    }

    fun deleteCategory(category: CategoryEntity) {
        viewModelScope.launch {
            try {
                categoriesApi.deleteCategory(category.id)
                categoryRepository.sync()
                mutationFeedback.show(R.string.mutation_category_deleted, category.name)
            } catch (_: Exception) {
                mutationFeedback.show(R.string.error_delete_category_failed, category.name)
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
