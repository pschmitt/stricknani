package blue.anika.wolle.ui.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blue.anika.wolle.R
import blue.anika.wolle.data.api.dto.TRASH_RETENTION_DAYS
import blue.anika.wolle.data.api.dto.TrashItemDto
import blue.anika.wolle.data.media.MediaUrlResolver
import blue.anika.wolle.data.repository.EveryoneScope
import blue.anika.wolle.data.repository.TrashException
import blue.anika.wolle.data.repository.TrashFailure
import blue.anika.wolle.data.repository.TrashRef
import blue.anika.wolle.data.repository.TrashRepository
import blue.anika.wolle.data.repository.toTrashFailure
import blue.anika.wolle.sync.SyncScheduler
import blue.anika.wolle.ui.common.MutationFeedback
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface TrashPhase {
    data object Loading : TrashPhase

    data object Ready : TrashPhase

    data class Failed(val failure: TrashFailure) : TrashPhase
}

data class TrashState(
    val items: List<TrashItemDto> = emptyList(),
    val scope: TrashScope = TrashScope.Mine,
    val phase: TrashPhase = TrashPhase.Loading,
    val isRefreshing: Boolean = false,
    val retentionDays: Int = TRASH_RETENTION_DAYS,
    /** The item put back last: the list offers to open it. */
    val restored: TrashItemDto? = null,
    /** The item whose Restore or Delete forever request is in flight. */
    val busy: TrashRef? = null,
)

@HiltViewModel
class TrashViewModel
@Inject
constructor(
    private val repository: TrashRepository,
    private val everyoneScope: EveryoneScope,
    private val syncScheduler: SyncScheduler,
    private val feedback: MutationFeedback,
    private val mediaUrlResolver: MediaUrlResolver,
) : ViewModel() {
    private val _state = MutableStateFlow(TrashState())
    val state: StateFlow<TrashState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        // The same Mine / All choice as the project and yarn lists (administrators only; the
        // website's trash follows it too). A change reloads the trash in the other scope.
        viewModelScope.launch {
            everyoneScope.state
                .map { if (it.available && it.enabled) TrashScope.All else TrashScope.Mine }
                .distinctUntilChanged()
                .collect { scope ->
                    _state.update {
                        it.copy(
                            scope = scope,
                            items = emptyList(),
                            restored = null,
                            phase = TrashPhase.Loading,
                        )
                    }
                    load(userInitiated = false)
                }
        }
    }

    fun avatarUrl(path: String?): String? = mediaUrlResolver.resolve(path)

    fun refresh() = load(userInitiated = true)

    fun retry() = load(userInitiated = false)

    private fun load(userInitiated: Boolean) {
        loadJob?.cancel()
        val scope = _state.value.scope
        _state.update {
            it.copy(
                isRefreshing = userInitiated,
                phase = if (it.items.isEmpty()) TrashPhase.Loading else it.phase,
            )
        }
        loadJob = viewModelScope.launch {
            try {
                val list = repository.list(scope.wire)
                _state.update {
                    it.copy(
                        items = sortTrash(list.data),
                        retentionDays = list.meta.retentionDays,
                        phase = TrashPhase.Ready,
                        isRefreshing = false,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                val why = failure.toTrashFailure()
                val hadItems = _state.value.items.isNotEmpty()
                if (hadItems) feedback.show(R.string.error_trash_load_failed)
                _state.update {
                    if (hadItems) it.copy(isRefreshing = false)
                    else it.copy(phase = TrashPhase.Failed(why), isRefreshing = false)
                }
            }
        }
    }

    /** Puts the item back and syncs, so it reappears in its list (it never left the server). */
    fun restore(item: TrashItemDto) {
        if (_state.value.busy != null) return
        _state.update { it.copy(busy = TrashRef(item.kind, item.id)) }
        viewModelScope.launch {
            try {
                repository.restore(TrashRef(item.kind, item.id))
                syncScheduler.syncNow()
                _state.update { s ->
                    s.copy(
                        items = s.items.filterNot { it.kind == item.kind && it.id == item.id },
                        restored = item,
                    )
                }
                feedback.show(R.string.mutation_trash_restored)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                val why = failure.toTrashFailure()
                feedback.show(restoreFailureRes(why))
                // Gone from the trash already (restored elsewhere): the list is stale.
                if (why == TrashFailure.NotFound) load(userInitiated = false)
            } finally {
                _state.update { it.copy(busy = null) }
            }
        }
    }

    fun purge(item: TrashItemDto) {
        if (_state.value.busy != null) return
        _state.update { it.copy(busy = TrashRef(item.kind, item.id)) }
        viewModelScope.launch {
            try {
                repository.purge(TrashRef(item.kind, item.id))
                _state.update { s ->
                    s.copy(items = s.items.filterNot { it.kind == item.kind && it.id == item.id })
                }
                feedback.show(R.string.mutation_trash_purged)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                val why = failure.toTrashFailure()
                feedback.show(purgeFailureRes(why))
                if (why == TrashFailure.NotFound) load(userInitiated = false)
            } finally {
                _state.update { it.copy(busy = null) }
            }
        }
    }
}

/** Undo of a delete: restore from the trash, then sync so the item reappears. */
@HiltViewModel
class TrashUndoViewModel
@Inject
constructor(
    private val repository: TrashRepository,
    private val syncScheduler: SyncScheduler,
    private val feedback: MutationFeedback,
) : ViewModel() {
    fun undo(ref: TrashRef) {
        viewModelScope.launch {
            try {
                restoreWithRetry(ref)
                syncScheduler.syncNow()
                feedback.show(R.string.mutation_trash_restored)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                feedback.show(restoreFailureRes(failure.toTrashFailure()))
            }
        }
    }

    /**
     * A project or yarn is deleted by the write queue, which may not have reached the server when
     * Undo is tapped right away: the first "not in the trash" answers are retried a few times.
     */
    private suspend fun restoreWithRetry(ref: TrashRef) {
        var attempt = 0
        while (true) {
            try {
                repository.restore(ref)
                return
            } catch (failure: TrashException) {
                if (failure.failure != TrashFailure.NotFound || ++attempt >= UNDO_ATTEMPTS) {
                    throw failure
                }
                delay(UNDO_RETRY_MILLIS)
            }
        }
    }

    private companion object {
        const val UNDO_ATTEMPTS = 4
        const val UNDO_RETRY_MILLIS = 1_500L
    }
}
