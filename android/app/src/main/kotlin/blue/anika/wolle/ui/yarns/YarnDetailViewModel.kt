package blue.anika.wolle.ui.yarns

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import blue.anika.wolle.R
import blue.anika.wolle.data.api.dto.YarnDto
import blue.anika.wolle.data.db.entity.YarnEntity
import blue.anika.wolle.data.media.MediaUrlResolver
import blue.anika.wolle.data.repository.HistoryKind
import blue.anika.wolle.data.repository.HistoryRepository
import blue.anika.wolle.data.repository.ItemLoad
import blue.anika.wolle.data.repository.ProjectRepository
import blue.anika.wolle.data.repository.SharedKind
import blue.anika.wolle.data.repository.SharingRepository
import blue.anika.wolle.data.repository.YarnRepository
import blue.anika.wolle.data.settings.AppPreferencesRepository
import blue.anika.wolle.data.settings.DetailCardDomain
import blue.anika.wolle.data.settings.DetailCardOrderRepository
import blue.anika.wolle.sync.SyncScheduler
import blue.anika.wolle.ui.common.ErrorKind
import blue.anika.wolle.ui.common.MutationFeedback
import blue.anika.wolle.ui.common.ReadingTextSize
import blue.anika.wolle.ui.common.RefreshController
import blue.anika.wolle.ui.common.RefreshState
import blue.anika.wolle.ui.common.errorKindForStatus
import blue.anika.wolle.ui.common.isOfflineFailure
import blue.anika.wolle.ui.common.isUserInitiatedRefresh
import blue.anika.wolle.ui.history.HistoryController
import blue.anika.wolle.ui.navigation.Route
import blue.anika.wolle.ui.print.PrintImageLoader
import blue.anika.wolle.ui.print.PrintOptions
import blue.anika.wolle.ui.sharing.SharingController
import blue.anika.wolle.ui.yarns.print.YarnPrintDocument
import blue.anika.wolle.ui.yarns.print.YarnPrintLabels
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LinkedProject(val id: Int, val name: String, val previewUrl: String?)

sealed interface YarnDetailUiState {
    data object Loading : YarnDetailUiState

    data object NotFound : YarnDetailUiState

    /** Not cached and the server could not serve it: offline, forbidden or a server error. */
    data class LoadFailed(val kind: ErrorKind) : YarnDetailUiState

    data class Loaded(
        val entity: YarnEntity,
        val detail: YarnDto,
        val linkedProjects: List<LinkedProject>,
    ) : YarnDetailUiState
}

@HiltViewModel
class YarnDetailViewModel
@Inject
constructor(
    savedStateHandle: SavedStateHandle,
    private val yarnRepository: YarnRepository,
    private val projectRepository: ProjectRepository,
    private val mediaUrlResolver: MediaUrlResolver,
    private val syncScheduler: SyncScheduler,
    private val mutationFeedback: MutationFeedback,
    private val detailCardOrderRepository: DetailCardOrderRepository,
    private val printImageLoader: PrintImageLoader,
    private val appPreferences: AppPreferencesRepository,
    historyRepository: HistoryRepository,
    sharingRepository: SharingRepository,
) : ViewModel() {

    val yarnId = savedStateHandle.toRoute<Route.YarnDetail>().yarnId

    private val _deleted = MutableStateFlow(false)
    val deleted: StateFlow<Boolean> = _deleted.asStateFlow()

    /** The "Recent changes" section's state; online-only and loaded when it scrolls into view. */
    val history = HistoryController(viewModelScope, HistoryKind.Yarn, yarnId, historyRepository)

    /** Share sheet, Transfer and Leave (online only); leaving closes the screen. */
    val sharing =
        SharingController(
            scope = viewModelScope,
            kind = SharedKind.Yarn,
            id = yarnId,
            repository = sharingRepository,
            onLeft = {
                mutationFeedback.show(R.string.sharing_left)
                _deleted.value = true
            },
        )

    init {
        viewModelScope.launch {
            sharing.transferred.filterNotNull().collect { person ->
                mutationFeedback.show(R.string.sharing_transferred, person.name)
                sharing.consumeTransferred()
            }
        }
    }

    val cardOrder = detailCardOrderRepository.yarnOrder

    fun saveCardOrder(order: List<String>) {
        viewModelScope.launch { detailCardOrderRepository.setOrder(DetailCardDomain.YARN, order) }
    }

    private val refreshController = RefreshController(viewModelScope)
    val refreshState: StateFlow<RefreshState> = refreshController.state
    val isRefreshing: StateFlow<Boolean> =
        refreshState
            .map { it.isUserInitiatedRefresh() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), false)

    /**
     * Result of the cache-miss fallback; null while unchecked. A detail screen opens by id whatever
     * the Mine | Everyone list filter says (audit log, Home activity, deep links): when the row is
     * not in Room it is fetched from the server, otherwise this says why it is not shown.
     */
    private val cacheMiss = MutableStateFlow<ItemLoad?>(null)

    init {
        viewModelScope.launch {
            yarnRepository
                .observeById(yarnId)
                .map { it != null }
                .distinctUntilChanged()
                .collect { present ->
                    if (present) cacheMiss.value = null else resolveMissing()
                }
        }
    }

    private suspend fun resolveMissing() {
        cacheMiss.value = null
        val result = yarnRepository.loadIfMissing(yarnId)
        if (result == ItemLoad.Available) {
            // The linked projects of an item fetched on demand are cached too, so they show up.
            yarnRepository.observeById(yarnId).first()?.let { entity ->
                yarnRepository.decodeDetail(entity).projectIds.distinct().forEach {
                    projectRepository.loadIfMissing(it)
                }
            }
        }
        cacheMiss.value = result
    }

    /** Tries the server again after a [YarnDetailUiState.LoadFailed]. */
    fun retryLoad() {
        viewModelScope.launch { resolveMissing() }
    }

    // SNA-36: see `ProjectDetailViewModel`'s identical fix - keeps the JSON detail decode gated on
    // this yarn's own row actually changing, not every unrelated project write.
    private val detailFlow: Flow<Pair<YarnEntity, YarnDto>?> =
        yarnRepository.observeById(yarnId).distinctUntilChanged().map { entity ->
            entity?.let { it to yarnRepository.decodeDetail(it) }
        }

    val uiState: StateFlow<YarnDetailUiState> =
        combine(detailFlow, projectRepository.observeAllIncludingForeign(), cacheMiss) {
                detailPair,
                projects,
                miss ->
                if (detailPair == null) {
                    when (miss) {
                        null,
                        ItemLoad.Available -> YarnDetailUiState.Loading
                        ItemLoad.Gone -> YarnDetailUiState.NotFound
                        ItemLoad.Offline -> YarnDetailUiState.LoadFailed(ErrorKind.Offline)
                        is ItemLoad.Failed ->
                            YarnDetailUiState.LoadFailed(errorKindForStatus(miss.status))
                    }
                } else {
                    val (entity, detail) = detailPair
                    val linked =
                        projects
                            .filter { it.id in detail.projectIds }
                            .map { LinkedProject(it.id, it.name, it.previewUrl) }
                    YarnDetailUiState.Loaded(entity, detail, linked)
                }
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                YarnDetailUiState.Loading,
            )

    fun resolveMediaUrl(path: String?): String? = mediaUrlResolver.resolve(path)

    /** The reading text size step (see `ReadingTextSize`), kept across restarts. */
    val textStep: StateFlow<Int> =
        appPreferences.readingTextStep
            .map { ReadingTextSize.clamp(it) }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                ReadingTextSize.DEFAULT_STEP,
            )

    fun setTextStep(step: Int) {
        viewModelScope.launch { appPreferences.setReadingTextStep(ReadingTextSize.clamp(step)) }
    }

    /** The print dialog's last choices (yarns only use the image choice). */
    val printOptions: StateFlow<PrintOptions> =
        appPreferences.printIncludeImages
            .map { images -> PrintOptions(includeImages = images) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PrintOptions())

    /** The printable HTML for the loaded yarn, or null while it is not loaded. */
    suspend fun printHtml(options: PrintOptions, labels: YarnPrintLabels): String? {
        val state = uiState.value as? YarnDetailUiState.Loaded ?: return null
        appPreferences.setPrintChoices(content = null, includeImages = options.includeImages)
        val yarn = state.detail
        val embedded =
            printImageLoader.embed(
                YarnPrintDocument.imageUrls(yarn, options.includeImages, ::resolveMediaUrl)
            )
        return YarnPrintDocument.html(
            yarn = yarn,
            usedInProjectNames = state.linkedProjects.map { it.name },
            includeImages = options.includeImages,
            labels = labels,
            resolve = ::resolveMediaUrl,
            embeddedImages = embedded,
        )
    }

    /** Refreshes this yarn and only the projects currently linked from its latest detail. */
    fun refresh() {
        history.reloadIfLoaded()
        refreshController.refresh {
            val yarn = yarnRepository.refreshOne(yarnId)
            var changed = yarn.changed
            yarn.detail.projectIds.distinct().forEach { projectId ->
                changed = projectRepository.refreshOne(projectId).changed || changed
            }
            changed
        }
    }

    /** The web URL for this yarn (SNA-17), for the detail screen's share action. */
    fun shareUrl(): String? = mediaUrlResolver.resolve("/yarn/$yarnId")

    fun toggleFavorite() {
        val state = uiState.value
        if (state !is YarnDetailUiState.Loaded) return
        val wasFavorite = state.entity.isFavorite
        viewModelScope.launch {
            try {
                yarnRepository.toggleFavorite(state.entity, wasFavorite = wasFavorite)
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

    fun delete() {
        if (uiState.value !is YarnDetailUiState.Loaded) return
        viewModelScope.launch {
            try {
                yarnRepository.deleteYarn(yarnId)
                syncScheduler.replayThenSyncNow()
                mutationFeedback.show(R.string.mutation_yarn_deleted_queued)
                _deleted.value = true
            } catch (e: Exception) {
                mutationFeedback.show(R.string.error_delete_failed)
            }
        }
    }

    fun dismissRefreshFeedback() = refreshController.clearFeedback()

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
