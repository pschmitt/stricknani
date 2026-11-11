package blue.anika.wolle.ui.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blue.anika.wolle.R
import blue.anika.wolle.data.api.dto.ProjectListItemDto
import blue.anika.wolle.data.api.dto.ProjectStatus
import blue.anika.wolle.data.db.entity.CategoryEntity
import blue.anika.wolle.data.db.entity.ProjectEntity
import blue.anika.wolle.data.media.MediaUrlResolver
import blue.anika.wolle.data.repository.CategoryRepository
import blue.anika.wolle.data.repository.EveryoneScope
import blue.anika.wolle.data.repository.ProjectRepository
import blue.anika.wolle.data.repository.TrashRepository
import blue.anika.wolle.data.repository.toTrashFailure
import blue.anika.wolle.data.settings.SettingsRepository
import blue.anika.wolle.ui.common.MutationFeedback
import blue.anika.wolle.ui.common.OwnerBadgeData
import blue.anika.wolle.ui.common.OwnerChoice
import blue.anika.wolle.ui.common.RefreshController
import blue.anika.wolle.ui.common.RefreshState
import blue.anika.wolle.ui.common.RefreshTrigger
import blue.anika.wolle.ui.common.TRASHED_ONLY
import blue.anika.wolle.ui.common.TrashedList
import blue.anika.wolle.ui.common.filterTrashedProjects
import blue.anika.wolle.ui.common.isOfflineFailure
import blue.anika.wolle.ui.common.isUserInitiatedRefresh
import blue.anika.wolle.ui.common.ownerBadge
import blue.anika.wolle.ui.common.ownerChoiceFromArg
import blue.anika.wolle.ui.common.ownerChoiceMatches
import blue.anika.wolle.ui.common.ownerOptions
import blue.anika.wolle.ui.common.toArg
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal fun projectTags(
    projects: List<ProjectEntity>,
    decodeTags: (ProjectEntity) -> List<String>,
): List<String> =
    projects
        .asSequence()
        .flatMap { decodeTags(it).asSequence() }
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinctBy { it.lowercase() }
        .sortedWith(String.CASE_INSENSITIVE_ORDER)
        .toList()

internal fun filterProjects(
    entities: List<ProjectEntity>,
    query: String,
    category: String?,
    tag: String?,
    decodeTags: (ProjectEntity) -> List<String>,
    favoritesOnly: Boolean = false,
    status: String? = null,
    owner: OwnerChoice? = null,
    currentUserId: Long? = null,
): List<ProjectEntity> =
    entities
        .asSequence()
        .filter { !favoritesOnly || it.isFavorite }
        .filter { status == null || it.status == status }
        .filter { ownerChoiceMatches(owner, it.ownerId, currentUserId) }
        .filter { category == null || it.category == category }
        .filter {
            tag == null ||
                decodeTags(it).any { projectTag -> projectTag.equals(tag, ignoreCase = true) }
        }
        .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
        .toList()

@OptIn(FlowPreview::class)
@HiltViewModel
class ProjectsListViewModel
@Inject
constructor(
    private val projectRepository: ProjectRepository,
    private val categoryRepository: CategoryRepository,
    private val mediaUrlResolver: MediaUrlResolver,
    private val mutationFeedback: MutationFeedback,
    private val settingsRepository: SettingsRepository,
    private val trashRepository: TrashRepository,
    private val everyoneScope: EveryoneScope,
) : ViewModel() {

    /**
     * Local-only mode has no network or AI import, so the "waiting for review" cards are hidden.
     */
    val importsOffered: Boolean
        get() = !settingsRepository.isLocalMode

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // SNA-36: debounce the copy driving the filter, not the text field itself - see
    // SearchViewModel's identical fix for why.
    private val debouncedSearchQuery =
        searchQuery.debounce(SEARCH_DEBOUNCE_MILLIS).distinctUntilChanged()

    private val _selectedCategory = MutableStateFlow<String?>(null)
    val selectedCategory: StateFlow<String?> = _selectedCategory.asStateFlow()

    private val _selectedTag = MutableStateFlow<String?>(null)
    val selectedTag: StateFlow<String?> = _selectedTag.asStateFlow()

    private val _favoritesOnly = MutableStateFlow(false)
    val favoritesOnly: StateFlow<Boolean> = _favoritesOnly.asStateFlow()

    /** `in_progress`, `done` or null for every status; same filter as the web list's pills. */
    private val _statusFilter = MutableStateFlow<String?>(null)
    val statusFilter: StateFlow<String?> = _statusFilter.asStateFlow()

    /** Whose projects (Mine, Shared with me, one owner); null shows everybody's. */
    private val _ownerChoice = MutableStateFlow<OwnerChoice?>(null)
    val ownerChoice: StateFlow<OwnerChoice?> = _ownerChoice.asStateFlow()

    /**
     * The Trash filter: only projects in the trash (`?trashed=only` of the web page). Trashed
     * projects are not in Room, so [trashedProjects] fetches them online.
     */
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

    val categories: StateFlow<List<CategoryEntity>> =
        categoryRepository
            .observeAll()
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                emptyList(),
            )

    private val allProjects: StateFlow<List<ProjectEntity>> =
        projectRepository
            .observeAll()
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                emptyList(),
            )

    val tags: StateFlow<List<String>> =
        allProjects
            .map { entities -> projectTags(entities, projectRepository::decodeTags) }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                emptyList(),
            )

    /** The switch-like filters travel together so one `combine` carries them. */
    private data class FlagFilters(
        val favoritesOnly: Boolean = false,
        val status: String? = null,
        val owner: OwnerChoice? = null,
        val currentUserId: Long? = null,
    )

    private val flagFilters: StateFlow<FlagFilters> =
        combine(favoritesOnly, statusFilter, ownerChoice, _currentUserId) {
                favorites,
                status,
                owner,
                me ->
                FlagFilters(favorites, status, owner, me)
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                FlagFilters(),
            )

    /** The other owners of the projects this device holds: the owner chips. */
    val owners: StateFlow<List<OwnerBadgeData>> =
        combine(allProjects, _currentUserId) { entities, me ->
                ownerOptions(entities.asSequence().map { it.ownerBadge(me) })
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                emptyList(),
            )

    val projects: StateFlow<List<ProjectEntity>> =
        combine(allProjects, debouncedSearchQuery, selectedCategory, selectedTag, flagFilters) {
                entities,
                query,
                category,
                tag,
                flags ->
                filterProjects(
                    entities,
                    query,
                    category,
                    tag,
                    projectRepository::decodeTags,
                    flags.favoritesOnly,
                    flags.status,
                    flags.owner,
                    flags.currentUserId,
                )
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                emptyList(),
            )

    /** What one fetch of the trash asks the server for; [reload] makes a retry a new request. */
    private data class TrashRequest(
        val trashedOnly: Boolean,
        val owner: String?,
        val status: String?,
        val scope: String,
        val reload: Int,
    )

    private val trashReload = MutableStateFlow(0)
    private val fetchedTrash = MutableStateFlow<TrashedList<ProjectListItemDto>>(TrashedList.Idle)

    /**
     * The trashed projects of the Trash filter, narrowed by search, category, tag and favourites.
     */
    val trashedProjects: StateFlow<TrashedList<ProjectListItemDto>> =
        combine(fetchedTrash, debouncedSearchQuery, selectedCategory, selectedTag, favoritesOnly) {
                fetched,
                query,
                category,
                tag,
                favorites ->
                if (fetched is TrashedList.Ready) {
                    TrashedList.Ready(
                        filterTrashedProjects(fetched.items, query, category, tag, favorites)
                    )
                } else {
                    fetched
                }
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                TrashedList.Idle,
            )

    init {
        refresh(trigger = RefreshTrigger.Automatic)
        viewModelScope.launch {
            combine(
                    trashedOnly,
                    ownerChoice,
                    statusFilter,
                    everyoneScope.state.map { it.available && it.enabled }.distinctUntilChanged(),
                    trashReload,
                ) { trashed, owner, status, everyone, reload ->
                    TrashRequest(
                        trashed,
                        owner.toArg(),
                        status,
                        if (everyone) "all" else "mine",
                        reload,
                    )
                }
                .distinctUntilChanged()
                .collectLatest { request -> loadTrash(request) }
        }
    }

    private suspend fun loadTrash(request: TrashRequest) {
        if (!request.trashedOnly) {
            fetchedTrash.value = TrashedList.Idle
            return
        }
        fetchedTrash.value = TrashedList.Loading
        fetchedTrash.value =
            try {
                TrashedList.Ready(
                    trashRepository.listTrashedProjects(
                        owner = request.owner,
                        status = request.status,
                        scope = request.scope,
                    )
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                TrashedList.Failed(failure.toTrashFailure())
            }
    }

    /** Fetches the trash again (the retry of the Trash filter's error state). */
    fun retryTrash() {
        trashReload.value += 1
    }

    fun previewUrl(entity: ProjectEntity): String? = mediaUrlResolver.resolve(entity.previewUrl)

    fun previewUrl(item: ProjectListItemDto): String? = mediaUrlResolver.resolve(item.previewUrl)

    fun tagsOf(entity: ProjectEntity): List<String> =
        runCatching { projectRepository.decodeTags(entity) }.getOrDefault(emptyList())

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    fun onCategorySelected(category: String?) {
        _selectedCategory.value = category
    }

    fun onFavoritesOnlyChange(favoritesOnly: Boolean) {
        _favoritesOnly.value = favoritesOnly
    }

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
     * Preselects the filters from the screen's navigation arguments: [owner] is `me`, `shared` or a
     * user id, [status] `in_progress` or `done`. Once per screen instance, so a rotation does not
     * bring back a filter the user cleared.
     */
    fun applyInitialFilters(owner: String?, status: String?, trashed: String? = null) {
        if (initialFiltersApplied) return
        initialFiltersApplied = true
        if (trashed == TRASHED_ONLY) _trashedOnly.value = true
        ownerChoiceFromArg(owner)?.let { _ownerChoice.value = it }
        status
            ?.takeIf { it == ProjectStatus.IN_PROGRESS || it == ProjectStatus.DONE }
            ?.let {
                _statusFilter.value = it
            }
    }

    private var initialFiltersApplied = false

    /** Selecting the active status pill again clears it, like the category and tag pills. */
    fun onStatusSelected(status: String?) {
        _statusFilter.value = if (_statusFilter.value == status) null else status
    }

    /**
     * "All": every filter off (category, tag, favourites). The Mine chip is reset by the screen.
     */
    fun clearFilters() {
        _selectedCategory.value = null
        _selectedTag.value = null
        _favoritesOnly.value = false
        _statusFilter.value = null
        _ownerChoice.value = null
        _trashedOnly.value = false
    }

    fun onTagSelected(tag: String?) {
        _selectedTag.value = if (_selectedTag.value == tag) null else tag
    }

    fun refresh(trigger: RefreshTrigger = RefreshTrigger.UserInitiated) {
        if (trigger != RefreshTrigger.Automatic) retryTrash()
        refreshController.refresh(trigger = trigger) {
            categoryRepository.sync()
            projectRepository.sync()
        }
    }

    fun toggleFavorite(entity: ProjectEntity) {
        val wasFavorite = entity.isFavorite
        viewModelScope.launch {
            try {
                projectRepository.toggleFavorite(entity, wasFavorite = wasFavorite)
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
