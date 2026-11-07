package blue.anika.wolle.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blue.anika.wolle.data.media.MediaUrlResolver
import blue.anika.wolle.data.repository.ActivityRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/** The full "Activity" page behind Home's "See all": the same feed, paged 20 rows at a time. */
@HiltViewModel
class ActivityViewModel
@Inject
constructor(
    activityRepository: ActivityRepository,
    private val mediaUrlResolver: MediaUrlResolver,
) : ViewModel() {
    private val controller =
        ActivityController(
            viewModelScope,
            activityRepository::page,
            pageSize = ACTIVITY_FULL_PAGE_SIZE,
        )

    val state: StateFlow<ActivityUiState> = controller.state

    init {
        controller.reload()
    }

    fun reload() = controller.reload()

    fun loadMore() = controller.loadMore()

    fun previewUrl(path: String?): String? = mediaUrlResolver.resolve(path)
}
