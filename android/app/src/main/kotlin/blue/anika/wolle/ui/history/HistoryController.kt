package blue.anika.wolle.ui.history

import blue.anika.wolle.data.api.dto.AuditDetailDto
import blue.anika.wolle.data.api.dto.AuditEventDto
import blue.anika.wolle.data.repository.HistoryKind
import blue.anika.wolle.data.repository.HistoryRepository
import blue.anika.wolle.data.repository.HistoryResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

sealed interface HistoryDetailState {
    data object Loading : HistoryDetailState

    data object Failed : HistoryDetailState

    data class Loaded(val detail: AuditDetailDto) : HistoryDetailState
}

enum class HistoryPhase {
    /** Nothing requested yet: the section has not scrolled into view. */
    Idle,
    Loading,
    Ready,
    Failed,
    /** The server has no history endpoint: the section is not shown at all. */
    Hidden,
}

data class HistoryUiState(
    val phase: HistoryPhase = HistoryPhase.Idle,
    val events: List<AuditEventDto> = emptyList(),
    val page: Int = 0,
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
    val moreFailed: Boolean = false,
    val showAll: Boolean = false,
    val details: Map<Long, HistoryDetailState> = emptyMap(),
    /** The event whose diff is open in the bottom sheet. */
    val selected: AuditEventDto? = null,
    val currentEmail: String? = null,
)

/**
 * The state of a detail page that has no history section (the read-only page of a trashed item).
 */
val NoHistory: StateFlow<HistoryUiState> =
    MutableStateFlow(HistoryUiState(phase = HistoryPhase.Hidden)).asStateFlow()

/**
 * Loads and holds the "Recent changes" of one project or yarn. The section calls [ensureLoaded]
 * when it first composes, which in the lazy detail list means when it scrolls into view. Owned by
 * the detail screen's view model; nothing is persisted.
 */
class HistoryController(
    private val scope: CoroutineScope,
    private val kind: HistoryKind,
    private val id: Int,
    private val repository: HistoryRepository,
) {
    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    private val detailPermits = Semaphore(DETAIL_CONCURRENCY)
    private var listJob: Job? = null

    fun ensureLoaded() {
        if (_state.value.phase == HistoryPhase.Idle) reload()
    }

    /** Pull-to-refresh: refresh the history too, but only if the section was ever opened. */
    fun reloadIfLoaded() {
        if (_state.value.phase != HistoryPhase.Idle) reload()
    }

    /** Reloads the first page, for pull-to-refresh and after a failure. */
    fun reload() {
        listJob?.cancel()
        _state.update {
            it.copy(
                phase = if (it.events.isEmpty()) HistoryPhase.Loading else it.phase,
                moreFailed = false,
            )
        }
        listJob = scope.launch {
            when (val result = repository.page(kind, id, 1)) {
                is HistoryResult.Ok ->
                    _state.update {
                        it.copy(
                            phase = HistoryPhase.Ready,
                            events = result.value.data,
                            page = result.value.meta.page,
                            hasMore = result.value.meta.hasMore,
                            loadingMore = false,
                            details = emptyMap(),
                            currentEmail = repository.currentEmail,
                        )
                    }
                HistoryResult.Unsupported -> _state.update { it.copy(phase = HistoryPhase.Hidden) }
                is HistoryResult.Failed ->
                    _state.update {
                        // Keep what is on screen when a refresh fails.
                        if (it.events.isEmpty()) it.copy(phase = HistoryPhase.Failed) else it
                    }
            }
            prefetchVisible()
        }
    }

    fun showAll() {
        _state.update { it.copy(showAll = true) }
        prefetchVisible()
    }

    fun showFewer() = _state.update { it.copy(showAll = false) }

    fun loadMore() {
        val current = _state.value
        if (!current.hasMore || current.loadingMore) return
        _state.update { it.copy(loadingMore = true, moreFailed = false) }
        scope.launch {
            when (val result = repository.page(kind, id, current.page + 1)) {
                is HistoryResult.Ok ->
                    _state.update {
                        it.copy(
                            events = mergeEvents(it.events, result.value.data),
                            page = result.value.meta.page,
                            hasMore = result.value.meta.hasMore,
                            loadingMore = false,
                        )
                    }
                else -> _state.update { it.copy(loadingMore = false, moreFailed = true) }
            }
            prefetchVisible()
        }
    }

    fun open(event: AuditEventDto) {
        _state.update { it.copy(selected = event) }
        // An event with no recorded changes (and no file) has nothing to fetch.
        if (event.hasChanges && event.media == null) loadDetail(event.id, force = true)
    }

    fun close() = _state.update { it.copy(selected = null) }

    fun loadDetail(eventId: Long, force: Boolean = false) {
        val existing = _state.value.details[eventId]
        if (existing is HistoryDetailState.Loaded || existing == HistoryDetailState.Loading) return
        if (existing == HistoryDetailState.Failed && !force) return
        _state.update { it.copy(details = it.details + (eventId to HistoryDetailState.Loading)) }
        scope.launch {
            val next = detailPermits.withPermit {
                when (val result = repository.detail(kind, id, eventId)) {
                    is HistoryResult.Ok -> HistoryDetailState.Loaded(result.value)
                    else -> HistoryDetailState.Failed
                }
            }
            _state.update { it.copy(details = it.details + (eventId to next)) }
        }
    }

    /** Fetches the field details of the rows on screen so each can say what changed. */
    private fun prefetchVisible() {
        val current = _state.value
        visibleEvents(current.events, current.showAll)
            .filter { it.hasChanges }
            .forEach { loadDetail(it.id) }
    }

    private companion object {
        const val DETAIL_CONCURRENCY = 3
    }
}
