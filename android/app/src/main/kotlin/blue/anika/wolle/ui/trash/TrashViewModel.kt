package blue.anika.wolle.ui.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blue.anika.wolle.R
import blue.anika.wolle.data.api.ApiCapabilities
import blue.anika.wolle.data.api.dto.TRASH_RETENTION_DAYS
import blue.anika.wolle.data.api.dto.TrashItemDto
import blue.anika.wolle.data.db.dao.PendingMutationDao
import blue.anika.wolle.data.media.MediaUrlResolver
import blue.anika.wolle.data.repository.EveryoneScope
import blue.anika.wolle.data.repository.ProjectRepository
import blue.anika.wolle.data.repository.QueuedDeleteUndo
import blue.anika.wolle.data.repository.TrashException
import blue.anika.wolle.data.repository.TrashFailure
import blue.anika.wolle.data.repository.TrashKind
import blue.anika.wolle.data.repository.TrashRef
import blue.anika.wolle.data.repository.TrashRepository
import blue.anika.wolle.data.repository.YarnRepository
import blue.anika.wolle.data.repository.toTrashFailure
import blue.anika.wolle.sync.SyncScheduler
import blue.anika.wolle.ui.common.DangerousActionGuard
import blue.anika.wolle.ui.common.MutationFeedback
import blue.anika.wolle.ui.common.TrashUndo
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

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
    capabilities: ApiCapabilities,
    private val guard: DangerousActionGuard,
) : ViewModel() {
    /** The signed-in user's id; null while unknown (a legacy server). */
    val currentUserId: StateFlow<Long?> =
        capabilities.state
            .map { it.user?.id }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

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

    /** Permanent deletion: after the dialog, behind the device unlock. */
    fun purge(item: TrashItemDto) =
        guard.confirm(R.string.unlock_reason_purge) { purgeConfirmed(item) }

    private fun purgeConfirmed(item: TrashItemDto) {
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

/**
 * Undo of a delete: puts the item back, then syncs so it reappears.
 *
 * * The delete is **still in the write queue** (offline, or not replayed yet): it never reached the
 *   server, so the queued delete is cancelled and the cached row is put back.
 * * The delete **was sent**: the server's restore endpoint is called. Without a connection the
 *   restore is queued and goes out when the app is online again.
 */
@HiltViewModel
class TrashUndoViewModel
@Inject
constructor(
    private val repository: TrashRepository,
    private val projectRepository: ProjectRepository,
    private val yarnRepository: YarnRepository,
    private val pendingMutationDao: PendingMutationDao,
    private val json: Json,
    private val syncScheduler: SyncScheduler,
    private val feedback: MutationFeedback,
) : ViewModel() {
    fun undo(undo: TrashUndo) {
        if (undo.viaQueue) undoSaved(undo.items) else undo.items.forEach(::undo)
    }

    /**
     * Steps and photos a save removed: the removal may still be queued, so the restores are queued
     * behind it and replay in order. A removal the server never saw answers "not in the trash",
     * which the replay ignores.
     */
    private fun undoSaved(items: List<TrashRef>) {
        viewModelScope.launch {
            try {
                items.forEach { QueuedDeleteUndo.queueRestore(pendingMutationDao, json, it) }
                syncScheduler.replayThenSyncNow()
                feedback.show(R.string.mutation_trash_restore_queued)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                feedback.show(R.string.error_trash_restore_failed)
            }
        }
    }

    fun undo(ref: TrashRef) {
        viewModelScope.launch {
            try {
                if (cancelQueuedDelete(ref)) {
                    syncScheduler.syncNow()
                    feedback.show(R.string.mutation_trash_restored)
                } else if (hasQueuedDelete(ref)) {
                    // Queued by an older app version, without a snapshot to put back: restore
                    // behind the delete once both have reached the server.
                    queueRestore(ref)
                } else {
                    restoreOnline(ref)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                feedback.show(restoreFailureRes(failure.toTrashFailure()))
            }
        }
    }

    private suspend fun cancelQueuedDelete(ref: TrashRef): Boolean =
        when (ref.kind) {
            TrashKind.Project.wire -> projectRepository.undoQueuedDelete(ref.id.toInt())
            TrashKind.Yarn.wire -> yarnRepository.undoQueuedDelete(ref.id.toInt())
            else -> false
        }

    private suspend fun hasQueuedDelete(ref: TrashRef): Boolean =
        when (ref.kind) {
            TrashKind.Project.wire -> projectRepository.hasQueuedDelete(ref.id.toInt())
            TrashKind.Yarn.wire -> yarnRepository.hasQueuedDelete(ref.id.toInt())
            else -> false
        }

    private suspend fun restoreOnline(ref: TrashRef) {
        try {
            restoreWithRetry(ref)
        } catch (failure: TrashException) {
            if (failure.failure != TrashFailure.Offline) throw failure
            queueRestore(ref)
            return
        }
        syncScheduler.syncNow()
        feedback.show(R.string.mutation_trash_restored)
    }

    private suspend fun queueRestore(ref: TrashRef) {
        QueuedDeleteUndo.queueRestore(pendingMutationDao, json, ref)
        syncScheduler.replayThenSyncNow()
        feedback.show(R.string.mutation_trash_restore_queued)
    }

    /**
     * A delete that was being sent when Undo is tapped may not be on the server yet: the first "not
     * in the trash" answers are retried a few times.
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
