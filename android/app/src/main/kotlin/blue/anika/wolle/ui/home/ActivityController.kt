package blue.anika.wolle.ui.home

import blue.anika.wolle.data.api.dto.ActivityItemDto
import blue.anika.wolle.data.api.dto.ActivityPageDto
import blue.anika.wolle.data.repository.HistoryResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ActivityPhase {
    /** Nothing requested yet. */
    Idle,
    Loading,
    Ready,
    /** Offline or the server failed and nothing is on screen: a compact one-line note. */
    Unavailable,
    /** The server has no activity endpoint, or the user is signed out: no section at all. */
    Hidden,
}

data class ActivityUiState(
    val phase: ActivityPhase = ActivityPhase.Idle,
    val items: List<ActivityItemDto> = emptyList(),
    val hasMore: Boolean = false,
    val nextBefore: Long? = null,
    val loadingMore: Boolean = false,
    val moreFailed: Boolean = false,
)

/** The paging transitions, pure so they are unit-tested. */
internal fun ActivityUiState.startReload(): ActivityUiState =
    copy(
        phase = if (items.isEmpty()) ActivityPhase.Loading else phase,
        moreFailed = false,
        loadingMore = false,
    )

internal fun ActivityUiState.withFirstPage(page: ActivityPageDto): ActivityUiState =
    ActivityUiState(
        phase = ActivityPhase.Ready,
        items = page.data,
        hasMore = page.meta.hasMore,
        nextBefore = page.meta.nextBefore,
    )

internal fun ActivityUiState.withMorePage(page: ActivityPageDto): ActivityUiState =
    copy(
        items = mergeActivity(items, page.data),
        hasMore = page.meta.hasMore,
        nextBefore = page.meta.nextBefore,
        loadingMore = false,
    )

/** A failed refresh keeps what is on screen; with nothing on screen it becomes the quiet note. */
internal fun ActivityUiState.withReloadFailure(): ActivityUiState =
    if (items.isEmpty()) copy(phase = ActivityPhase.Unavailable, loadingMore = false) else this

/**
 * Loads and holds Home's "Recent activity". Owned by the Home view model; nothing is persisted.
 * [fetch] is `ActivityRepository.page`, injected so the paging can be tested.
 */
class ActivityController(
    private val scope: CoroutineScope,
    private val fetch: suspend (limit: Int, before: Long?) -> HistoryResult<ActivityPageDto>,
    private val pageSize: Int = ACTIVITY_PAGE_SIZE,
) {
    private val _state = MutableStateFlow(ActivityUiState())
    val state: StateFlow<ActivityUiState> = _state.asStateFlow()

    private var job: Job? = null
    private var moreJob: Job? = null

    /** Loads the newest page; also the pull-to-refresh path. */
    fun reload() {
        job?.cancel()
        moreJob?.cancel()
        _state.update { it.startReload() }
        job = scope.launch {
            when (val result = fetch(pageSize, null)) {
                is HistoryResult.Ok -> _state.update { it.withFirstPage(result.value) }
                HistoryResult.Unsupported ->
                    _state.value = ActivityUiState(phase = ActivityPhase.Hidden)
                is HistoryResult.Failed -> _state.update { it.withReloadFailure() }
            }
        }
    }

    /** Signed out: there is nothing to show, and nothing to keep. */
    fun hide() {
        job?.cancel()
        moreJob?.cancel()
        _state.value = ActivityUiState(phase = ActivityPhase.Hidden)
    }

    fun loadMore() {
        val current = _state.value
        if (!current.hasMore || current.loadingMore || current.nextBefore == null) return
        _state.update { it.copy(loadingMore = true, moreFailed = false) }
        moreJob = scope.launch {
            when (val result = fetch(pageSize, current.nextBefore)) {
                is HistoryResult.Ok -> _state.update { it.withMorePage(result.value) }
                else -> _state.update { it.copy(loadingMore = false, moreFailed = true) }
            }
        }
    }
}
