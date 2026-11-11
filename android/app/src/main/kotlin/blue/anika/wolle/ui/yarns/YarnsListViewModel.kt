package blue.anika.wolle.ui.yarns

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blue.anika.wolle.R
import blue.anika.wolle.data.db.entity.YarnEntity
import blue.anika.wolle.data.media.MediaUrlResolver
import blue.anika.wolle.data.repository.YarnRepository
import blue.anika.wolle.data.settings.SettingsRepository
import blue.anika.wolle.ui.common.MutationFeedback
import blue.anika.wolle.ui.common.OwnerBadgeData
import blue.anika.wolle.ui.common.OwnerChoice
import blue.anika.wolle.ui.common.RefreshController
import blue.anika.wolle.ui.common.RefreshState
import blue.anika.wolle.ui.common.RefreshTrigger
import blue.anika.wolle.ui.common.SuggestedFilters
import blue.anika.wolle.ui.common.SuggestionAction
import blue.anika.wolle.ui.common.SuggestionKind
import blue.anika.wolle.ui.common.TRASHED_ONLY
import blue.anika.wolle.ui.common.applying
import blue.anika.wolle.ui.common.isOfflineFailure
import blue.anika.wolle.ui.common.isUserInitiatedRefresh
import blue.anika.wolle.ui.common.ownerBadge
import blue.anika.wolle.ui.common.ownerChoiceFromArg
import blue.anika.wolle.ui.common.ownerChoiceMatches
import blue.anika.wolle.ui.common.ownerOptions
import blue.anika.wolle.ui.common.trashDaysLeft
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
@HiltViewModel
class YarnsListViewModel
@Inject
constructor(
    private val yarnRepository: YarnRepository,
    private val mediaUrlResolver: MediaUrlResolver,
    private val mutationFeedback: MutationFeedback,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    /**
     * Local-only mode has no network or AI import, so the "waiting for review" cards are hidden.
     */
    val importsOffered: Boolean
        get() = !settingsRepository.isLocalMode

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // SNA-36: see ProjectsListViewModel's identical fix.
    private val debouncedSearchQuery =
        searchQuery.debounce(SEARCH_DEBOUNCE_MILLIS).distinctUntilChanged()

    private val _favoritesOnly = MutableStateFlow(false)
    val favoritesOnly: StateFlow<Boolean> = _favoritesOnly.asStateFlow()

    /** Whose yarn (Mine, Shared with me, one owner); null shows everybody's. */
    private val _ownerChoice = MutableStateFlow<OwnerChoice?>(null)
    val ownerChoice: StateFlow<OwnerChoice?> = _ownerChoice.asStateFlow()

    /** The Trash filter: only yarn in the trash (`?trashed=only` of the web page). */
    private val _trashedOnly = MutableStateFlow(false)
    val trashedOnly: StateFlow<Boolean> = _trashedOnly.asStateFlow()

    /** The signed-in user, set by the screen from the composition's owner context. */
    private val _currentUserId = MutableStateFlow<Long?>(null)

    private val refreshController =
        RefreshController(
            viewModelScope,
            skipAutomatic = { settingsRepository.sessionExpired.value },
        )
    val refreshState: StateFlow<RefreshState> = refreshController.state
    val isRefreshing: StateFlow<Boolean> =
        refreshState
            .map { it.isUserInitiatedRefresh() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), false)

    private val allYarns: StateFlow<List<YarnEntity>> =
        yarnRepository
            .observeAll()
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                emptyList(),
            )

    /** The other owners of the yarn this device holds: the owner chips. */
    val owners: StateFlow<List<OwnerBadgeData>> =
        combine(allYarns, _currentUserId) { entities, me ->
                ownerOptions(entities.asSequence().map { it.ownerBadge(me) })
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                emptyList(),
            )

    val yarns: StateFlow<List<YarnEntity>> =
        combine(
                allYarns,
                debouncedSearchQuery,
                favoritesOnly,
                ownerChoice,
                _currentUserId,
                trashedOnly,
            ) { values ->
                @Suppress("UNCHECKED_CAST") val entities = values[0] as List<YarnEntity>
                val query = values[1] as String
                val favoritesOnly = values[2] as Boolean
                val owner = values[3] as OwnerChoice?
                val me = values[4] as Long?
                val trashedOnly = values[5] as Boolean
                entities
                    .asSequence()
                    .filter { !trashedOnly || isTrashed(it) }
                    .filter { !favoritesOnly || it.isFavorite }
                    .filter { ownerChoiceMatches(owner, it.ownerId, me) }
                    .filter {
                        query.isBlank() ||
                            it.name.contains(query, ignoreCase = true) ||
                            it.brand?.contains(query, ignoreCase = true) == true ||
                            it.colorway?.contains(query, ignoreCase = true) == true
                    }
                    .toList()
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                emptyList(),
            )

    init {
        refresh(trigger = RefreshTrigger.Automatic)
    }

    fun previewUrl(entity: YarnEntity): String? = mediaUrlResolver.resolve(entity.previewUrl)

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    /** "All": every filter off. */
    fun clearFilters() {
        _favoritesOnly.value = false
        _ownerChoice.value = null
        _trashedOnly.value = false
    }

    /** Whether a cached yarn is in the trash (its detail carries `trashed_at`). */
    private fun isTrashed(entity: YarnEntity): Boolean =
        runCatching { yarnRepository.decodeDetail(entity).trashedAt != null }.getOrDefault(false)

    /** Days left in the trash for the card badge; null outside the Trash filter. */
    fun trashDaysLeft(entity: YarnEntity): Int? =
        if (!_trashedOnly.value) null
        else
            runCatching {
                    val detail = yarnRepository.decodeDetail(entity)
                    trashDaysLeft(detail.trashedAt, detail.purgeAt, System.currentTimeMillis())
                }
                .getOrNull()

    fun onTrashedOnlyChange(trashedOnly: Boolean) {
        _trashedOnly.value = trashedOnly
    }

    fun setCurrentUserId(id: Long?) {
        _currentUserId.value = id
    }

    /** The owner chips; selecting the active one again clears it. */
    fun onOwnerSelected(choice: OwnerChoice?) {
        _ownerChoice.value = choice
    }

    /**
     * Picking a filter suggestion in the search field: that filter is set and the typed text, which
     * only served to find it, is dropped.
     */
    fun onSuggestionPicked(action: SuggestionAction) {
        val next =
            SuggestedFilters(
                    favoritesOnly = _favoritesOnly.value,
                    owner = _ownerChoice.value,
                    trashedOnly = _trashedOnly.value,
                )
                .applying(action, SuggestionKind.Yarns)
        _searchQuery.value = ""
        _favoritesOnly.value = next.favoritesOnly
        _ownerChoice.value = next.owner
        _trashedOnly.value = next.trashedOnly
    }

    /** Preselects an owner from the screen's navigation argument (`me`, `shared` or a user id). */
    fun applyInitialOwner(argument: String?, trashed: String? = null) {
        // Once per screen instance: a rotation must not bring back a filter the user cleared.
        if (initialOwnerApplied) return
        initialOwnerApplied = true
        if (trashed == TRASHED_ONLY) _trashedOnly.value = true
        ownerChoiceFromArg(argument)?.let { _ownerChoice.value = it }
    }

    private var initialOwnerApplied = false

    fun onFavoritesOnlyChange(favoritesOnly: Boolean) {
        _favoritesOnly.value = favoritesOnly
    }

    fun refresh(trigger: RefreshTrigger = RefreshTrigger.UserInitiated) {
        refreshController.refresh(trigger = trigger) { yarnRepository.sync() }
    }

    fun toggleFavorite(entity: YarnEntity) {
        val wasFavorite = entity.isFavorite
        viewModelScope.launch {
            try {
                yarnRepository.toggleFavorite(entity, wasFavorite = wasFavorite)
                mutationFeedback.show(
                    if (wasFavorite) R.string.mutation_favorite_removed
                    else R.string.mutation_favorite_added
                )
            } catch (e: Exception) {
                mutationFeedback.show(
                    if (e.isOfflineFailure()) R.string.mutation_favorite_offline
                    else R.string.error_favorite_failed
                )
            }
        }
    }

    fun dismissRefreshFeedback() = refreshController.clearFeedback()

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val SEARCH_DEBOUNCE_MILLIS = 250L
    }
}
