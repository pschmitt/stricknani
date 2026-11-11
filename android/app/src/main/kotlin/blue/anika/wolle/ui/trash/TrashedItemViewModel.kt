package blue.anika.wolle.ui.trash

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import blue.anika.wolle.R
import blue.anika.wolle.data.api.v2.ProjectV2Dto
import blue.anika.wolle.data.api.v2.ProjectsV2Api
import blue.anika.wolle.data.api.v2.YarnV2Dto
import blue.anika.wolle.data.api.v2.YarnsV2Api
import blue.anika.wolle.data.media.MediaUrlResolver
import blue.anika.wolle.data.repository.TrashFailure
import blue.anika.wolle.data.repository.TrashRef
import blue.anika.wolle.data.repository.TrashRepository
import blue.anika.wolle.data.repository.toTrashFailure
import blue.anika.wolle.sync.SyncScheduler
import blue.anika.wolle.ui.common.MutationFeedback
import blue.anika.wolle.ui.navigation.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the read-only page of a trashed project or yarn shows. */
sealed interface TrashedItemPhase {
    data object Loading : TrashedItemPhase

    data class Project(val project: ProjectV2Dto) : TrashedItemPhase

    data class Yarn(val yarn: YarnV2Dto) : TrashedItemPhase

    data class Failed(val failure: TrashFailure) : TrashedItemPhase
}

/** Where to go after Restore or Delete permanently. */
sealed interface TrashedItemResult {
    data object Restored : TrashedItemResult

    data object Purged : TrashedItemResult
}

/**
 * A project or yarn in the trash, fetched by id and never stored in Room (sync does not carry
 * trashed items). Only its owner and administrators can read it; everything else answers 404.
 */
@HiltViewModel
class TrashedItemViewModel
@Inject
constructor(
    savedStateHandle: SavedStateHandle,
    private val projectsApi: ProjectsV2Api,
    private val yarnsApi: YarnsV2Api,
    private val repository: TrashRepository,
    private val syncScheduler: SyncScheduler,
    private val feedback: MutationFeedback,
    private val mediaUrlResolver: MediaUrlResolver,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<Route.TrashedItem>()
    val kind: String = route.kind
    val id: Int = route.id
    private val ref = TrashRef(kind, id.toLong())

    private val _phase = MutableStateFlow<TrashedItemPhase>(TrashedItemPhase.Loading)
    val phase: StateFlow<TrashedItemPhase> = _phase.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _result = MutableStateFlow<TrashedItemResult?>(null)
    val result: StateFlow<TrashedItemResult?> = _result.asStateFlow()

    init {
        load()
    }

    fun mediaUrl(path: String?): String? = mediaUrlResolver.resolve(path)

    fun consumeResult() {
        _result.value = null
    }

    fun load() {
        _phase.value = TrashedItemPhase.Loading
        viewModelScope.launch {
            try {
                _phase.value =
                    if (kind == "yarn") TrashedItemPhase.Yarn(yarnsApi.getYarn(id).data)
                    else TrashedItemPhase.Project(projectsApi.getProject(id).data)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                _phase.value = TrashedItemPhase.Failed(failure.toTrashFailure())
            }
        }
    }

    /** Puts the item back, syncs and opens the normal page. */
    fun restore() {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                repository.restore(ref)
                syncScheduler.syncNow()
                feedback.show(R.string.mutation_trash_restored)
                _result.value = TrashedItemResult.Restored
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                feedback.show(restoreFailureRes(failure.toTrashFailure()))
            } finally {
                _busy.update { false }
            }
        }
    }

    fun purge() {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                repository.purge(ref)
                feedback.show(R.string.mutation_trash_purged)
                _result.value = TrashedItemResult.Purged
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                feedback.show(purgeFailureRes(failure.toTrashFailure()))
            } finally {
                _busy.update { false }
            }
        }
    }
}
