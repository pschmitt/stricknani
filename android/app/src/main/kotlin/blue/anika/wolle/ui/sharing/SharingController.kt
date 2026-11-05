package blue.anika.wolle.ui.sharing

import blue.anika.wolle.data.api.dto.PersonDto
import blue.anika.wolle.data.api.dto.ShareDto
import blue.anika.wolle.data.repository.ShareRole
import blue.anika.wolle.data.repository.SharedKind
import blue.anika.wolle.data.repository.SharingError
import blue.anika.wolle.data.repository.SharingException
import blue.anika.wolle.data.repository.SharingGateway
import blue.anika.wolle.ui.common.NetworkStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Search debounce for the people picker. */
internal const val PEOPLE_SEARCH_DEBOUNCE_MILLIS = 300L

/** The searchable people directory (avatars and names only, never emails). */
data class PeopleSearchState(
    val query: String = "",
    val people: List<PersonDto> = emptyList(),
    val loading: Boolean = false,
    val hasMore: Boolean = false,
    val page: Int = 1,
    val failed: Boolean = false,
    /** The query [people] answers; the list is only shown while it equals [query]. */
    val resultsFor: String? = null,
) {
    /** Results of an older query are never shown for the current one: typing shows matches only. */
    val visiblePeople: List<PersonDto>
        get() = if (resultsFor == query) people else emptyList()
}

/**
 * Paged, debounced `GET /people` search, shared by the share picker and the transfer dialog. Every
 * request carries a generation: an answer (or failure) of an older request is dropped even if it
 * still arrives, and a newer request cancels the older one.
 */
class PeopleSearch(private val scope: CoroutineScope, private val repository: SharingGateway) {
    private val _state = MutableStateFlow(PeopleSearchState())
    val state: StateFlow<PeopleSearchState> = _state.asStateFlow()

    private var job: Job? = null
    private var generation = 0

    /** Loads the first page now (the sheet just opened). */
    fun start() = load(query = _state.value.query, page = 1, debounce = false)

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
        load(query, page = 1, debounce = true)
    }

    fun loadMore() {
        val current = _state.value
        if (current.loading || !current.hasMore) return
        load(current.query, current.page + 1, debounce = false)
    }

    private fun load(query: String, page: Int, debounce: Boolean) {
        job?.cancel()
        val mine = ++generation
        job = scope.launch {
            if (debounce) delay(PEOPLE_SEARCH_DEBOUNCE_MILLIS)
            _state.update { it.copy(loading = true, failed = false) }
            try {
                val result = repository.people(query, page)
                _state.update { current ->
                    if (mine != generation || current.query != query) current
                    else
                        current.copy(
                            people =
                                if (page == 1) result.items
                                else (current.people + result.items).distinctBy { it.id },
                            loading = false,
                            hasMore = result.hasMore,
                            page = page,
                            resultsFor = query,
                        )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                if (mine == generation) _state.update { it.copy(loading = false, failed = true) }
            }
        }
    }
}

enum class SharingPhase {
    Idle,
    Loading,
    Ready,
    Failed,
}

data class SharingUiState(
    val phase: SharingPhase = SharingPhase.Idle,
    val owner: PersonDto? = null,
    val shares: List<ShareDto> = emptyList(),
    /** The device has no working connection: every action is disabled and says why. */
    val offline: Boolean = false,
    /** People with a call in flight, so their row disables itself. */
    val busy: Set<Long> = emptySet(),
    val error: SharingError? = null,
    val newRole: ShareRole = ShareRole.Viewer,
    /** The person picked to share with; the picker collapses to them until Change or Share. */
    val selected: PersonDto? = null,
)

data class TransferUiState(
    val selected: PersonDto? = null,
    val moveLinkedYarns: Boolean = false,
    val submitting: Boolean = false,
    val error: SharingError? = null,
    /** The transfer went through; the sheet and dialog close. */
    val done: Boolean = false,
)

/**
 * State of one item's Share sheet, Transfer dialog and Leave action. Owned by the detail screen's
 * view model; nothing is persisted. Sharing is never queued: offline, everything is disabled with
 * an explanation. The server decides who may do what, so a refused call shows its error and the
 * sheet reloads the real shares.
 */
class SharingController(
    private val scope: CoroutineScope,
    private val kind: SharedKind,
    private val id: Int,
    private val repository: SharingGateway,
    private val hasConnection: () -> Boolean? = { NetworkStatus.hasValidatedInternet },
    private val onLeft: () -> Unit = {},
) {
    private val _state = MutableStateFlow(SharingUiState())
    val state: StateFlow<SharingUiState> = _state.asStateFlow()

    /** People picked for sharing on the sheet. */
    val people = PeopleSearch(scope, repository)

    private val _transfer = MutableStateFlow(TransferUiState())
    val transfer: StateFlow<TransferUiState> = _transfer.asStateFlow()

    /** People for the transfer dialog's own picker. */
    val transferPeople = PeopleSearch(scope, repository)

    private val _transferred = MutableStateFlow<PersonDto?>(null)

    /** The new owner after a successful transfer, until [consumeTransferred]. */
    val transferred: StateFlow<PersonDto?> = _transferred.asStateFlow()

    fun consumeTransferred() {
        _transferred.value = null
    }

    /** The sheet opened (or came back online): loads the shares and the first people page. */
    fun open() {
        val offline = hasConnection() == false
        _state.update { it.copy(offline = offline, error = null) }
        if (offline) return
        reload()
        people.start()
    }

    /**
     * Loads only the shares, quietly, so the Share row can show the avatars of the people the item
     * is shared with. Does nothing offline, while loading or once loaded.
     */
    fun preload() {
        if (_state.value.phase != SharingPhase.Idle || hasConnection() == false) return
        reload()
    }

    fun reload() {
        _state.update { it.copy(phase = SharingPhase.Loading) }
        scope.launch {
            try {
                val list = repository.shares(kind, id)
                _state.update {
                    it.copy(
                        phase = SharingPhase.Ready,
                        owner = list.owner,
                        shares = list.shares.sortedBy { share -> share.user.name.lowercase() },
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: SharingException) {
                fail(failure.error, phase = SharingPhase.Failed)
            }
        }
    }

    fun setNewRole(role: ShareRole) = _state.update { it.copy(newRole = role) }

    fun select(person: PersonDto?) = _state.update { it.copy(selected = person) }

    /** Shares with the picked person using the chosen role; the picker resets afterwards. */
    fun shareSelected() {
        val person = _state.value.selected ?: return
        add(person)
    }

    private fun add(person: PersonDto) =
        mutate(person.id) {
            val share = repository.add(kind, id, person.id, _state.value.newRole)
            _state.update { s ->
                s.copy(
                    shares = (s.shares.filterNot { it.user.id == person.id } + share).sorted(),
                    selected = null,
                )
            }
        }

    fun setRole(userId: Long, role: ShareRole) =
        mutate(userId) {
            val share = repository.changeRole(kind, id, userId, role)
            _state.update { s ->
                s.copy(shares = s.shares.map { if (it.user.id == userId) share else it })
            }
        }

    fun remove(userId: Long) =
        mutate(userId) {
            repository.revoke(kind, id, userId)
            _state.update { s -> s.copy(shares = s.shares.filterNot { it.user.id == userId }) }
        }

    /** Leaves the caller's own share; [onLeft] runs once the server confirmed. */
    fun leave() {
        scope.launch {
            try {
                repository.leave(kind, id)
                onLeft()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: SharingException) {
                _state.update { it.copy(error = failure.error) }
            }
        }
    }

    fun openTransfer() {
        _transfer.value = TransferUiState()
        transferPeople.start()
    }

    fun selectNewOwner(person: PersonDto?) = _transfer.update { it.copy(selected = person) }

    fun setMoveLinkedYarns(move: Boolean) = _transfer.update { it.copy(moveLinkedYarns = move) }

    /** Hands the item over; applies at once on the server, the caller stays an editor. */
    fun confirmTransfer() {
        val current = _transfer.value
        val target = current.selected ?: return
        if (current.submitting) return
        _transfer.update { it.copy(submitting = true, error = null) }
        scope.launch {
            try {
                repository.transfer(kind, id, target.id, current.moveLinkedYarns)
                _transfer.value = TransferUiState(done = true)
                _transferred.value = target
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: SharingException) {
                _transfer.update { it.copy(submitting = false, error = failure.error) }
                if (failure.error == SharingError.Offline) {
                    _state.update { it.copy(offline = true) }
                }
            }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    private fun mutate(userId: Long, block: suspend () -> Unit) {
        if (_state.value.busy.contains(userId)) return
        _state.update { it.copy(busy = it.busy + userId, error = null) }
        scope.launch {
            try {
                block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: SharingException) {
                fail(failure.error, phase = null)
                // The real list may differ from what the sheet shows (a role changed elsewhere).
                if (failure.error != SharingError.Offline) reload()
            } finally {
                _state.update { it.copy(busy = it.busy - userId) }
            }
        }
    }

    private fun fail(error: SharingError, phase: SharingPhase?) {
        _state.update {
            it.copy(
                phase = phase ?: it.phase,
                error = error,
                offline = it.offline || error == SharingError.Offline,
            )
        }
    }

    private fun List<ShareDto>.sorted(): List<ShareDto> = sortedBy { it.user.name.lowercase() }
}
