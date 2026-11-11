package blue.anika.wolle.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blue.anika.wolle.R
import blue.anika.wolle.data.api.dto.AuditDetailDto
import blue.anika.wolle.data.api.dto.AuditEventDto
import blue.anika.wolle.data.media.MediaUrlResolver
import blue.anika.wolle.data.repository.AdminException
import blue.anika.wolle.data.repository.AdminFailure
import blue.anika.wolle.data.repository.AdminRepository
import blue.anika.wolle.data.repository.TrashRepository
import blue.anika.wolle.data.repository.toTrashFailure
import blue.anika.wolle.sync.SyncScheduler
import blue.anika.wolle.ui.common.MutationFeedback
import blue.anika.wolle.ui.trash.restoreFailureRes
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The filters the audit API supports: `entity` (project/yarn/category) and an exact `action`. */
data class AuditFilter(val entity: String? = null, val action: String? = null) {
    val isActive: Boolean
        get() = entity != null || action != null
}

/** What the list area shows. */
sealed interface AuditPhase {
    /** First load with nothing to show yet. */
    data object Loading : AuditPhase

    data object Ready : AuditPhase

    /** First load failed; [failure] says why. */
    data class Failed(val failure: AdminFailure) : AuditPhase
}

data class AuditListState(
    val events: List<AuditEventDto> = emptyList(),
    val filter: AuditFilter = AuditFilter(),
    val phase: AuditPhase = AuditPhase.Loading,
    val page: Int = 0,
    val hasMore: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    /** Paging failed; the rows already loaded stay visible with a "try again" row. */
    val loadMoreFailure: AdminFailure? = null,
)

sealed interface AuditDetailState {
    data object Closed : AuditDetailState

    /** [event] is the row that was tapped, shown immediately while the detail loads. */
    data class Loading(val event: AuditEventDto) : AuditDetailState

    data class Loaded(val detail: AuditDetailDto) : AuditDetailState

    data class Failed(val event: AuditEventDto, val failure: AdminFailure) : AuditDetailState
}

@HiltViewModel
class AdminAuditViewModel
@Inject
constructor(
    private val repository: AdminRepository,
    private val mediaUrlResolver: MediaUrlResolver,
    private val trashRepository: TrashRepository,
    private val syncScheduler: SyncScheduler,
    private val feedback: MutationFeedback,
) : ViewModel() {
    private val _state = MutableStateFlow(AuditListState())
    val state: StateFlow<AuditListState> = _state.asStateFlow()

    private val _detail = MutableStateFlow<AuditDetailState>(AuditDetailState.Closed)
    val detail: StateFlow<AuditDetailState> = _detail.asStateFlow()

    private var listJob: Job? = null
    private var detailJob: Job? = null

    init {
        load(reset = true)
    }

    fun avatarUrl(path: String?): String? = mediaUrlResolver.resolve(path)

    fun refresh() = load(reset = true, userInitiated = true)

    fun retry() = load(reset = true)

    fun loadMore() {
        val current = _state.value
        if (!current.hasMore || current.isLoadingMore || current.isRefreshing) return
        if (current.phase != AuditPhase.Ready) return
        load(reset = false)
    }

    fun setEntity(entity: String?) = setFilter(_state.value.filter.copy(entity = entity))

    fun setAction(action: String?) =
        setFilter(_state.value.filter.copy(action = action?.trim()?.ifBlank { null }))

    fun clearFilters() = setFilter(AuditFilter())

    private fun setFilter(filter: AuditFilter) {
        if (filter == _state.value.filter) return
        _state.update { AuditListState(filter = filter) }
        load(reset = true)
    }

    private fun load(reset: Boolean, userInitiated: Boolean = false) {
        listJob?.cancel()
        val filter = _state.value.filter
        val page = if (reset) 1 else _state.value.page + 1
        _state.update {
            if (reset) {
                it.copy(
                    isRefreshing = userInitiated,
                    isLoadingMore = false,
                    loadMoreFailure = null,
                    phase = if (it.events.isEmpty()) AuditPhase.Loading else it.phase,
                )
            } else {
                it.copy(isLoadingMore = true, loadMoreFailure = null)
            }
        }
        listJob = viewModelScope.launch {
            try {
                val result = repository.auditPage(page, filter.entity, filter.action)
                _state.update {
                    it.copy(
                        events = if (reset) result.data else it.events + result.data,
                        page = result.meta.page,
                        hasMore = result.meta.hasMore,
                        phase = AuditPhase.Ready,
                        isRefreshing = false,
                        isLoadingMore = false,
                        loadMoreFailure = null,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                val why = (failure as? AdminException)?.failure ?: AdminFailure.Other
                _state.update {
                    if (reset && it.events.isEmpty()) {
                        it.copy(
                            phase = AuditPhase.Failed(why),
                            isRefreshing = false,
                            isLoadingMore = false,
                        )
                    } else {
                        it.copy(
                            isRefreshing = false,
                            isLoadingMore = false,
                            loadMoreFailure = why,
                        )
                    }
                }
            }
        }
    }

    fun openEvent(event: AuditEventDto) {
        detailJob?.cancel()
        _detail.value = AuditDetailState.Loading(event)
        detailJob = viewModelScope.launch {
            try {
                _detail.value = AuditDetailState.Loaded(repository.auditDetail(event.id))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                _detail.value =
                    AuditDetailState.Failed(
                        event,
                        (failure as? AdminException)?.failure ?: AdminFailure.Other,
                    )
            }
        }
    }

    /** Restores the item of an event that sits in the trash, then syncs and refreshes the list. */
    fun restore(event: AuditEventDto) {
        val ref = event.restoreRef() ?: return
        viewModelScope.launch {
            try {
                trashRepository.restore(ref)
                syncScheduler.syncNow()
                feedback.show(R.string.mutation_trash_restored)
                closeEvent()
                load(reset = true, userInitiated = false)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                feedback.show(restoreFailureRes(failure.toTrashFailure()))
            }
        }
    }

    fun closeEvent() {
        detailJob?.cancel()
        _detail.value = AuditDetailState.Closed
    }
}
