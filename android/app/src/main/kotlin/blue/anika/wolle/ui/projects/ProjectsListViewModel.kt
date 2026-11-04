package blue.anika.wolle.ui.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blue.anika.wolle.R
import blue.anika.wolle.data.api.ApiCapabilities
import blue.anika.wolle.data.api.ImportAvailability
import blue.anika.wolle.data.api.dto.ProjectWriteRequest
import blue.anika.wolle.data.api.importAvailability
import blue.anika.wolle.data.db.entity.CategoryEntity
import blue.anika.wolle.data.db.entity.ProjectEntity
import blue.anika.wolle.data.db.entity.YarnEntity
import blue.anika.wolle.data.media.MediaUrlResolver
import blue.anika.wolle.data.repository.CategoryRepository
import blue.anika.wolle.data.repository.ProjectImporter
import blue.anika.wolle.data.repository.ProjectRepository
import blue.anika.wolle.data.repository.YarnRepository
import blue.anika.wolle.data.settings.SettingsRepository
import blue.anika.wolle.sync.SyncScheduler
import blue.anika.wolle.ui.common.MutationFeedback
import blue.anika.wolle.ui.common.RefreshController
import blue.anika.wolle.ui.common.RefreshState
import blue.anika.wolle.ui.common.RefreshTrigger
import blue.anika.wolle.ui.common.importImagePath
import blue.anika.wolle.ui.common.isOfflineFailure
import blue.anika.wolle.ui.common.isUserInitiatedRefresh
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
): List<ProjectEntity> =
    entities
        .asSequence()
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
    private val projectImporter: ProjectImporter,
    yarnRepository: YarnRepository,
    private val apiCapabilities: ApiCapabilities,
    private val syncScheduler: SyncScheduler,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

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

    val projects: StateFlow<List<ProjectEntity>> =
        combine(allProjects, debouncedSearchQuery, selectedCategory, selectedTag) {
                entities,
                query,
                category,
                tag ->
                filterProjects(
                    entities,
                    query,
                    category,
                    tag,
                    projectRepository::decodeTags,
                )
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                emptyList(),
            )

    private val importController =
        ProjectImportController(
            scope = viewModelScope,
            importer = projectImporter,
        ) { preview ->
            // The dialog's review already holds the final notes and yarn choices.
            val projectId = projectRepository.createProject(preview.request)
            syncScheduler.replayThenSyncNow()
            mutationFeedback.show(
                R.string.mutation_project_imported_queued,
                preview.request.name,
            )
            projectId
        }

    val importState: StateFlow<ProjectImportState> = importController.state

    /** Which import options the server offers; the dialog greys out the ones it does not. */
    val importAvailability: StateFlow<ImportAvailability> =
        apiCapabilities.state
            .map { it.importAvailability() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, ImportAvailability())

    init {
        refresh(trigger = RefreshTrigger.Automatic)
    }

    fun previewUrl(entity: ProjectEntity): String? = mediaUrlResolver.resolve(entity.previewUrl)

    fun tagsOf(entity: ProjectEntity): List<String> =
        runCatching { projectRepository.decodeTags(entity) }.getOrDefault(emptyList())

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    fun onCategorySelected(category: String?) {
        _selectedCategory.value = category
    }

    fun onTagSelected(tag: String?) {
        _selectedTag.value = if (_selectedTag.value == tag) null else tag
    }

    fun refresh(trigger: RefreshTrigger = RefreshTrigger.UserInitiated) {
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

    fun addCategory(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            try {
                categoryRepository.create(name.trim())
                categoryRepository.sync()
                mutationFeedback.show(R.string.mutation_category_created, name.trim())
            } catch (e: Exception) {
                mutationFeedback.show(R.string.error_create_category_failed, name)
            }
        }
    }

    fun startImport(url: String) = importController.start(url)

    fun retryImport() = importController.retry()

    fun confirmImport(
        selectedImages: List<String>,
        reviewed: ProjectWriteRequest,
        selectedAssets: List<String>,
    ) = importController.confirm(selectedImages, reviewed, selectedAssets)

    /** The user's own yarns, for the stash picker of the import review. */
    val stashYarns: StateFlow<List<YarnEntity>> =
        yarnRepository
            .observeAll()
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                emptyList(),
            )

    fun stashYarnPreviewUrl(yarn: YarnEntity): String? = mediaUrlResolver.resolve(yarn.previewUrl)

    /** The server's thumbnail proxy for a picture of the imported page, as a loadable URL. */
    fun importThumbnailUrl(url: String): String? = mediaUrlResolver.resolve(importImagePath(url))

    fun cancelImport() = importController.cancel()

    fun dismissImport() = importController.dismiss()

    fun dismissRefreshFeedback() = refreshController.clearFeedback()

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val SEARCH_DEBOUNCE_MILLIS = 250L
    }
}
