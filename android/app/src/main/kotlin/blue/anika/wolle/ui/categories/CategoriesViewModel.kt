package blue.anika.wolle.ui.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blue.anika.wolle.data.db.entity.CategoryEntity
import blue.anika.wolle.data.repository.CategoryRepository
import blue.anika.wolle.ui.common.RefreshController
import blue.anika.wolle.ui.common.RefreshState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

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
class CategoriesViewModel @Inject constructor(private val categoryRepository: CategoryRepository) :
    ViewModel() {
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
                    isRefreshing = refresh is RefreshState.Refreshing,
                )
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                CategoriesUiState(),
            )

    init {
        refresh()
    }

    fun refresh() {
        refreshController.refresh {
            val changed = categoryRepository.sync()
            initialSyncComplete.value = true
            changed
        }
    }

    fun dismissRefreshFeedback() = refreshController.clearFeedback()

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
