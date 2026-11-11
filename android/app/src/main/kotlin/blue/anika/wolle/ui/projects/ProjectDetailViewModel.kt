package blue.anika.wolle.ui.projects

import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import blue.anika.wolle.R
import blue.anika.wolle.data.api.ApiCapabilities
import blue.anika.wolle.data.api.dto.ProjectDto
import blue.anika.wolle.data.api.dto.ProjectStatus
import blue.anika.wolle.data.api.v2.ProjectTimerDto
import blue.anika.wolle.data.api.v2.ProjectTimerRequest
import blue.anika.wolle.data.api.v2.ProjectsV2Api
import blue.anika.wolle.data.db.entity.ProjectEntity
import blue.anika.wolle.data.media.MediaUrlResolver
import blue.anika.wolle.data.repository.HistoryKind
import blue.anika.wolle.data.repository.HistoryRepository
import blue.anika.wolle.data.repository.ItemLoad
import blue.anika.wolle.data.repository.ProjectRepository
import blue.anika.wolle.data.repository.SharedKind
import blue.anika.wolle.data.repository.SharingRepository
import blue.anika.wolle.data.repository.YarnRepository
import blue.anika.wolle.data.repository.access
import blue.anika.wolle.data.settings.AppPreferencesRepository
import blue.anika.wolle.data.settings.DetailCardDomain
import blue.anika.wolle.data.settings.DetailCardOrderRepository
import blue.anika.wolle.data.settings.SettingsRepository
import blue.anika.wolle.data.timer.ProjectTimerLoadFailure
import blue.anika.wolle.data.timer.ProjectTimerReminderInterval
import blue.anika.wolle.data.timer.ProjectTimerReminderRepository
import blue.anika.wolle.data.timer.ProjectTimerReminderScheduler
import blue.anika.wolle.data.timer.RunningTimers
import blue.anika.wolle.data.timer.classifyProjectTimerLoadFailure
import blue.anika.wolle.data.timer.isTimerStarter
import blue.anika.wolle.data.timer.projectTimerReminderDeadline
import blue.anika.wolle.sync.SyncScheduler
import blue.anika.wolle.ui.common.ErrorKind
import blue.anika.wolle.ui.common.MutationFeedback
import blue.anika.wolle.ui.common.ReadingTextSize
import blue.anika.wolle.ui.common.RefreshController
import blue.anika.wolle.ui.common.RefreshState
import blue.anika.wolle.ui.common.errorKindForStatus
import blue.anika.wolle.ui.common.isOfflineFailure
import blue.anika.wolle.ui.common.isUserInitiatedRefresh
import blue.anika.wolle.ui.common.yarnsOwnedBy
import blue.anika.wolle.ui.history.HistoryController
import blue.anika.wolle.ui.navigation.Route
import blue.anika.wolle.ui.print.PrintContent
import blue.anika.wolle.ui.print.PrintImageLoader
import blue.anika.wolle.ui.print.PrintOptions
import blue.anika.wolle.ui.projects.print.PrintLabels
import blue.anika.wolle.ui.projects.print.ProjectPrintDocument
import blue.anika.wolle.ui.sharing.SharingController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import retrofit2.HttpException

/** A yarn linked to the project; [notes] and [link] come from its cached detail, like the web. */
data class LinkedYarn(
    val id: Int,
    val name: String,
    val previewUrl: String?,
    val notes: String? = null,
    val link: String? = null,
    /** Brand and colourway, as on the web's yarn card ("Garnstudio · Farbe 22"). */
    val subtitle: String? = null,
)

sealed interface ProjectDetailUiState {
    data object Loading : ProjectDetailUiState

    data object NotFound : ProjectDetailUiState

    /** Not cached and the server could not serve it: offline, forbidden or a server error. */
    data class LoadFailed(val kind: ErrorKind) : ProjectDetailUiState

    data class Loaded(
        val entity: ProjectEntity,
        val detail: ProjectDto,
        val linkedYarns: List<LinkedYarn>,
    ) : ProjectDetailUiState
}

@HiltViewModel
class ProjectDetailViewModel
@Inject
constructor(
    savedStateHandle: SavedStateHandle,
    private val projectRepository: ProjectRepository,
    private val yarnRepository: YarnRepository,
    private val mediaUrlResolver: MediaUrlResolver,
    private val syncScheduler: SyncScheduler,
    private val mutationFeedback: MutationFeedback,
    private val detailCardOrderRepository: DetailCardOrderRepository,
    private val printImageLoader: PrintImageLoader,
    private val appPreferences: AppPreferencesRepository,
    private val projectsV2Api: ProjectsV2Api,
    private val reminderPreferences: ProjectTimerReminderRepository,
    private val reminderScheduler: ProjectTimerReminderScheduler,
    private val runningTimers: RunningTimers,
    private val settingsRepository: SettingsRepository,
    historyRepository: HistoryRepository,
    sharingRepository: SharingRepository,
    private val capabilities: ApiCapabilities,
) : ViewModel() {

    val projectId = savedStateHandle.toRoute<Route.ProjectDetail>().projectId

    data class TimerState(
        val totalSeconds: Long = 0,
        val startedAt: String? = null,
        val receivedAtElapsedMillis: Long = 0,
        val loading: Boolean = true,
        val saving: Boolean = false,
        val error: Boolean = false,
        val unavailable: Boolean = false,
        val projectMissing: Boolean = false,
        /** This user started the running timer: only then do reminders and notifications apply. */
        val startedByMe: Boolean = false,
        val reminderIntervalMinutes: Int = ProjectTimerReminderInterval.HOUR_1,
        val reminderDeadlineMillis: Long? = null,
    ) {
        val running: Boolean
            get() = startedAt != null
    }

    private val _timer = MutableStateFlow(TimerState())
    private val timerTicks = flow {
        while (true) {
            emit(SystemClock.elapsedRealtime())
            delay(1_000)
        }
    }
    val timerState: StateFlow<TimerState> = _timer.asStateFlow()
    val projectTimersEnabled: StateFlow<Boolean> =
        appPreferences.projectTimersEnabled.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            false,
        )
    val timerSeconds: StateFlow<Long> =
        combine(_timer, timerTicks) { timer, now ->
                if (timer.running && !timer.loading)
                    timer.totalSeconds +
                        ((now - timer.receivedAtElapsedMillis) / 1_000).coerceAtLeast(0)
                else timer.totalSeconds
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), 0)

    private var timerRequestInFlight = false
    private val timersEnabledNow: Boolean
        get() = projectTimersEnabled.value

    private val _deleted = MutableStateFlow(false)
    val deleted: StateFlow<Boolean> = _deleted.asStateFlow()

    /** The "Recent changes" section's state; online-only and loaded when it scrolls into view. */
    val history =
        HistoryController(viewModelScope, HistoryKind.Project, projectId, historyRepository)

    /**
     * Share sheet, Transfer and Leave (online only). Leaving closes the screen like a delete does,
     * since the project is gone for this user.
     */
    /** Local-only mode: nobody to share with, and the link would point at this phone. */
    val sharingOffered: Boolean = !settingsRepository.isLocalMode

    val sharing =
        SharingController(
            scope = viewModelScope,
            kind = SharedKind.Project,
            id = projectId,
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

    suspend fun refreshTimer() {
        if (
            appPreferences.projectTimersEnabled.first() &&
                !_timer.value.unavailable &&
                currentUserId.first() != null &&
                reminderPreferences.currentAccountKey() != null &&
                capabilities.useV2()
        )
            loadTimer()
    }

    private suspend fun loadTimer() {
        if (
            timerRequestInFlight ||
                !appPreferences.projectTimersEnabled.first() ||
                _timer.value.unavailable
        )
            return
        val userId = currentUserId.first() ?: return
        val accountKey = reminderPreferences.currentAccountKey() ?: return
        try {
            val response = projectsV2Api.getProjectTimer(projectId).data
            if (
                !timerRequestInFlight &&
                    appPreferences.projectTimersEnabled.first() &&
                    currentUserId.first() == userId &&
                    reminderPreferences.currentAccountKey() == accountKey
            )
                applyTimerResponse(response, accountKey)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            if (
                !timerRequestInFlight &&
                    appPreferences.projectTimersEnabled.first() &&
                    currentUserId.first() == userId &&
                    reminderPreferences.currentAccountKey() == accountKey
            )
                _timer.value = _timer.value.withTimerFailure(failure)
        }
    }

    private fun reloadTimerAfterAccountSwitch(previousAccountKey: String?) {
        viewModelScope.launch {
            val currentAccountKey = reminderPreferences.currentAccountKey()
            if (currentAccountKey != previousAccountKey && currentAccountKey != null) loadTimer()
        }
    }

    private fun ProjectTimerDto.toTimerState(userId: Long) =
        TimerState(
            totalSeconds = totalSeconds.coerceIn(0, MAX_TIMER_SECONDS),
            startedAt = startedAt,
            receivedAtElapsedMillis = SystemClock.elapsedRealtime(),
            startedByMe = isTimerStarter(this, userId),
            loading = false,
        )

    private fun TimerState.withTimerFailure(failure: Exception): TimerState {
        val classification =
            if (failure is HttpException)
                classifyProjectTimerLoadFailure(
                    failure.code(),
                    runCatching { failure.response()?.errorBody()?.string() }.getOrNull(),
                )
            else ProjectTimerLoadFailure.RequestFailed
        return copy(
            loading = false,
            saving = false,
            error = classification == ProjectTimerLoadFailure.RequestFailed,
            unavailable = classification == ProjectTimerLoadFailure.UnsupportedServer,
            projectMissing = classification == ProjectTimerLoadFailure.ProjectMissing,
        )
    }

    private suspend fun applyTimerResponse(
        response: ProjectTimerDto,
        expectedAccountKey: String,
        resetReminderDeadline: Boolean = false,
    ): Boolean {
        if (!appPreferences.projectTimersEnabled.first()) return false
        val userId = currentUserId.first() ?: return false
        val accountKey = reminderPreferences.currentAccountKey() ?: return false
        if (accountKey != expectedAccountKey) return false
        val previous = _timer.value
        val interval = reminderPreferences.interval(accountKey, projectId)
        if (
            reminderPreferences.currentAccountKey() != accountKey ||
                !appPreferences.projectTimersEnabled.first()
        )
            return false
        val timerProject = projectRepository.observeById(projectId).first()
        val canEditTimer = timerProject?.access()?.canEdit == true
        if (
            reminderPreferences.currentAccountKey() != accountKey ||
                !appPreferences.projectTimersEnabled.first()
        )
            return false
        // The persistent notification follows the timer: posted while it runs, gone when it stops.
        runningTimers.onTimerObserved(
            projectId,
            timerProject?.name.orEmpty(),
            canEditTimer,
            response,
        )
        if (response.startedAt == null) {
            reminderScheduler.cancel(projectId, accountKey)
            if (
                currentUserId.first() == userId &&
                    reminderPreferences.currentAccountKey() == accountKey
            ) {
                _timer.value =
                    response.toTimerState(userId).copy(reminderIntervalMinutes = interval)
                return true
            }
            return false
        }
        val startedAt = requireNotNull(response.startedAt)
        if (!canEditTimer || !isTimerStarter(response, userId)) {
            reminderScheduler.cancel(projectId, accountKey)
            if (
                currentUserId.first() == userId &&
                    reminderPreferences.currentAccountKey() == accountKey
            ) {
                _timer.value =
                    response.toTimerState(userId).copy(reminderIntervalMinutes = interval)
                return true
            }
            return false
        }
        val sameSession = !resetReminderDeadline && previous.startedAt == startedAt
        val storedSession =
            if (resetReminderDeadline) null
            else reminderPreferences.scheduledStartedAt(accountKey, projectId)
        var deadline = if (sameSession) previous.reminderDeadlineMillis else null
        if (deadline == null && storedSession == startedAt)
            deadline = reminderPreferences.deadline(accountKey, projectId)
        if (
            reminderPreferences.currentAccountKey() != accountKey ||
                !appPreferences.projectTimersEnabled.first()
        )
            return false
        if (interval == ProjectTimerReminderInterval.OFF) {
            reminderScheduler.cancel(projectId, accountKey)
            deadline = null
        } else if (resetReminderDeadline || storedSession != startedAt || deadline == null) {
            val deadlineFromStart =
                initialReminderDeadline(
                    startedAt,
                    response.serverTime,
                    interval,
                    resetReminderDeadline,
                )
            reminderScheduler.schedule(
                projectId,
                startedAt,
                interval,
                deadlineFromStart,
                accountKey,
            )
            deadline = deadlineFromStart
        }
        if (
            currentUserId.first() == userId && reminderPreferences.currentAccountKey() == accountKey
        ) {
            _timer.value =
                response
                    .toTimerState(userId)
                    .copy(
                        reminderIntervalMinutes = interval,
                        reminderDeadlineMillis = deadline,
                    )
            return true
        }
        return false
    }

    private fun initialReminderDeadline(
        startedAt: String,
        serverTime: String,
        interval: Int,
        resetFromNow: Boolean = false,
    ): Long {
        val now = System.currentTimeMillis()
        return projectTimerReminderDeadline(startedAt, serverTime, interval, now, resetFromNow)
            ?: now + interval * 60_000L
    }

    fun setTimerRunning(running: Boolean, onComplete: (Boolean) -> Unit = {}) {
        if (timerRequestInFlight || _timer.value.unavailable || !timersEnabledNow) return
        timerRequestInFlight = true
        viewModelScope.launch {
            val userId = currentUserId.first()
            val accountKey = reminderPreferences.currentAccountKey()
            _timer.value = _timer.value.copy(saving = true, error = false)
            try {
                if (
                    !appPreferences.projectTimersEnabled.first() ||
                        userId == null ||
                        accountKey == null ||
                        !capabilities.useV2()
                )
                    throw IllegalStateException("No active v2 account")
                val action = if (running) "start" else "stop"
                val response =
                    projectsV2Api
                        .updateProjectTimer(
                            projectId,
                            ProjectTimerRequest(action),
                        )
                        .data
                if (
                    currentUserId.first() == userId &&
                        appPreferences.projectTimersEnabled.first() &&
                        reminderPreferences.currentAccountKey() == accountKey
                ) {
                    val applied = applyTimerResponse(response, accountKey)
                    if (
                        appPreferences.projectTimersEnabled.first() &&
                            reminderPreferences.currentAccountKey() == accountKey
                    )
                        onComplete(applied)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                if (
                    appPreferences.projectTimersEnabled.first() &&
                        currentUserId.first() == userId &&
                        reminderPreferences.currentAccountKey() == accountKey
                )
                    _timer.value = _timer.value.withTimerFailure(failure)
                if (
                    appPreferences.projectTimersEnabled.first() &&
                        currentUserId.first() == userId &&
                        reminderPreferences.currentAccountKey() == accountKey
                )
                    onComplete(false)
            } finally {
                timerRequestInFlight = false
                reloadTimerAfterAccountSwitch(accountKey)
            }
        }
    }

    fun setTimerTotal(totalSeconds: Long, onComplete: (Boolean) -> Unit) {
        if (
            timerRequestInFlight ||
                _timer.value.unavailable ||
                !timersEnabledNow ||
                totalSeconds !in 0..MAX_TIMER_SECONDS
        )
            return
        timerRequestInFlight = true
        viewModelScope.launch {
            val userId = currentUserId.first()
            val accountKey = reminderPreferences.currentAccountKey()
            _timer.value = _timer.value.copy(saving = true, error = false)
            try {
                if (
                    !appPreferences.projectTimersEnabled.first() ||
                        userId == null ||
                        accountKey == null ||
                        !capabilities.useV2()
                )
                    throw IllegalStateException("No active v2 account")
                val response =
                    projectsV2Api
                        .updateProjectTimer(
                            projectId,
                            ProjectTimerRequest("set_total", totalSeconds),
                        )
                        .data
                val matchesAccount =
                    currentUserId.first() == userId &&
                        appPreferences.projectTimersEnabled.first() &&
                        reminderPreferences.currentAccountKey() == accountKey
                val applied =
                    if (matchesAccount)
                        applyTimerResponse(response, accountKey, resetReminderDeadline = true)
                    else false
                if (
                    matchesAccount &&
                        appPreferences.projectTimersEnabled.first() &&
                        reminderPreferences.currentAccountKey() == accountKey
                )
                    onComplete(applied)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                val matchesAccount =
                    appPreferences.projectTimersEnabled.first() &&
                        currentUserId.first() == userId &&
                        reminderPreferences.currentAccountKey() == accountKey
                if (matchesAccount) _timer.value = _timer.value.withTimerFailure(failure)
                if (matchesAccount) onComplete(false)
            } finally {
                timerRequestInFlight = false
                reloadTimerAfterAccountSwitch(accountKey)
            }
        }
    }

    fun setTimerReminderInterval(intervalMinutes: Int) {
        if (!timersEnabledNow || intervalMinutes !in ProjectTimerReminderInterval.choices) return
        viewModelScope.launch {
            if (!appPreferences.projectTimersEnabled.first()) return@launch
            val accountKey = reminderPreferences.currentAccountKey() ?: return@launch
            reminderPreferences.setInterval(accountKey, projectId, intervalMinutes)
            val current = _timer.value
            if (
                current.running &&
                    current.startedByMe &&
                    current.startedAt != null &&
                    intervalMinutes != ProjectTimerReminderInterval.OFF
            ) {
                val deadline = System.currentTimeMillis() + intervalMinutes * 60_000L
                reminderScheduler.schedule(
                    projectId,
                    current.startedAt,
                    intervalMinutes,
                    deadline,
                    accountKey,
                )
                if (reminderPreferences.currentAccountKey() == accountKey)
                    _timer.value =
                        current.copy(
                            reminderIntervalMinutes = intervalMinutes,
                            reminderDeadlineMillis = deadline,
                        )
            } else {
                reminderScheduler.cancel(projectId, accountKey)
                if (reminderPreferences.currentAccountKey() == accountKey)
                    _timer.value =
                        current.copy(
                            reminderIntervalMinutes = intervalMinutes,
                            reminderDeadlineMillis = null,
                        )
            }
        }
    }

    fun snoozeTimerReminder() {
        if (!timersEnabledNow) return
        val current = _timer.value
        val startedAt = current.startedAt ?: return
        if (!current.startedByMe) return
        val interval = current.reminderIntervalMinutes
        if (interval == ProjectTimerReminderInterval.OFF) return
        viewModelScope.launch {
            if (!appPreferences.projectTimersEnabled.first()) return@launch
            val accountKey = reminderPreferences.currentAccountKey() ?: return@launch
            val deadline = System.currentTimeMillis() + interval * 60_000L
            reminderScheduler.snooze(projectId, startedAt, interval, deadline, accountKey)
            if (reminderPreferences.currentAccountKey() == accountKey)
                _timer.value = _timer.value.copy(reminderDeadlineMillis = deadline)
        }
    }

    private val currentUserId: Flow<Long?> =
        capabilities.state.map { it.user?.id }.distinctUntilChanged()

    /**
     * How many of this project's linked yarns its owner owns: the transfer dialog offers to move
     * exactly those (yarns of anyone else stay where they are).
     */
    val ownedLinkedYarnCount: StateFlow<Int> =
        combine(
                yarnRepository.observeAllIncludingForeign(),
                projectRepository.observeById(projectId).distinctUntilChanged(),
                currentUserId,
            ) { yarns, project, me ->
                if (project == null) 0
                else {
                    val linked = projectRepository.decodeDetail(project).yarnIds.toSet()
                    yarnsOwnedBy(yarns.filter { it.id in linked }, project.ownerId, me).size
                }
            }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), 0)

    val cardOrder = detailCardOrderRepository.projectOrder

    fun saveCardOrder(order: List<String>) {
        viewModelScope.launch {
            detailCardOrderRepository.setOrder(DetailCardDomain.PROJECT, order)
        }
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
            projectRepository
                .observeById(projectId)
                .map { it != null }
                .distinctUntilChanged()
                .collect { present ->
                    if (present) cacheMiss.value = null else resolveMissing()
                }
        }
    }

    private suspend fun resolveMissing() {
        cacheMiss.value = null
        val result = projectRepository.loadIfMissing(projectId)
        if (result == ItemLoad.Available) {
            // The linked yarns of an item fetched on demand are cached too, so they show up.
            projectRepository.observeById(projectId).first()?.let { entity ->
                projectRepository.decodeDetail(entity).yarnIds.distinct().forEach {
                    yarnRepository.loadIfMissing(it)
                }
            }
        }
        cacheMiss.value = result
    }

    /** Tries the server again after a [ProjectDetailUiState.LoadFailed]. */
    fun retryLoad() {
        viewModelScope.launch { resolveMissing() }
    }

    // SNA-36: decoding the full detail JSON (steps/images/attachments) is real work, so it's kept
    // in its own upstream step gated by distinctUntilChanged - Room re-emits observeById on *any*
    // write to the projects table (not just this row), and combine's downstream re-runs on every
    // unrelated yarn change too; without this, viewing a project's detail screen would re-decode
    // its JSON on every background sync tick and every yarn edit anywhere else in the app.
    private val detailFlow: Flow<Pair<ProjectEntity, ProjectDto>?> =
        projectRepository.observeById(projectId).distinctUntilChanged().map { entity ->
            entity?.let { it to projectRepository.decodeDetail(it) }
        }

    val uiState: StateFlow<ProjectDetailUiState> =
        combine(detailFlow, yarnRepository.observeAllIncludingForeign(), cacheMiss) {
                detailPair,
                yarns,
                miss ->
                if (detailPair == null) {
                    when (miss) {
                        null,
                        ItemLoad.Available -> ProjectDetailUiState.Loading
                        ItemLoad.Gone -> ProjectDetailUiState.NotFound
                        ItemLoad.Offline -> ProjectDetailUiState.LoadFailed(ErrorKind.Offline)
                        is ItemLoad.Failed ->
                            ProjectDetailUiState.LoadFailed(errorKindForStatus(miss.status))
                    }
                } else {
                    val (entity, detail) = detailPair
                    val linked =
                        yarns
                            .filter { it.id in detail.yarnIds }
                            .map { yarn ->
                                val yarnDetail =
                                    runCatching { yarnRepository.decodeDetail(yarn) }.getOrNull()
                                LinkedYarn(
                                    yarn.id,
                                    yarn.name,
                                    yarn.previewUrl,
                                    notes = yarnDetail?.notes?.takeIf { it.isNotBlank() },
                                    link = yarnDetail?.link,
                                    subtitle =
                                        listOfNotNull(yarn.brand, yarn.colorway)
                                            .filter { it.isNotBlank() }
                                            .joinToString(" · ")
                                            .ifBlank { null },
                                )
                            }
                    ProjectDetailUiState.Loaded(entity, detail, linked)
                }
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                ProjectDetailUiState.Loading,
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

    /** The print dialog's last choices, so it opens the way it was used. */
    val printOptions: StateFlow<PrintOptions> =
        combine(appPreferences.printContent, appPreferences.printIncludeImages) { content, images ->
                PrintOptions(
                    content =
                        content?.let { runCatching { PrintContent.valueOf(it) }.getOrNull() }
                            ?: PrintContent.EVERYTHING,
                    includeImages = images,
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PrintOptions())

    // This collector can run synchronously on Main.immediate as soon as its input flows emit.
    // Keep it after currentUserId, uiState, and every other property used by loadTimer() and
    // applyTimerResponse() have been initialized.
    init {
        viewModelScope.launch {
            combine(
                    settingsRepository.credentials,
                    capabilities.state,
                    appPreferences.projectTimersEnabled,
                ) { credentials, state, timersEnabled ->
                    Pair(
                        "${credentials.serverUrl}\n${credentials.apiToken}\n${state.user?.id}\n${state.currentTokenId}",
                        timersEnabled,
                    )
                }
                .distinctUntilChanged()
                .collectLatest { (identity, enabled) ->
                    val userId = currentUserId.first()
                    val configured = identity.substringBefore('\n').isNotBlank()
                    _timer.value = TimerState(loading = enabled && configured && userId != null)
                    if (!enabled) {
                        reminderScheduler.cancelAll()
                        runningTimers.clearAll()
                        return@collectLatest
                    }
                    if (!configured || userId == null || !capabilities.useV2()) return@collectLatest
                    val accountKey = reminderPreferences.currentAccountKey()
                    val interval =
                        accountKey?.let { reminderPreferences.interval(it, projectId) }
                            ?: ProjectTimerReminderInterval.HOUR_1
                    _timer.value = _timer.value.copy(reminderIntervalMinutes = interval)
                    loadTimer()
                }
        }
    }

    // The notification's Stop button stopped this project's timer: show it.
    init {
        viewModelScope.launch {
            runningTimers.stopped.collect { if (it == projectId) loadTimer() }
        }
    }

    /**
     * The printable HTML for the loaded project, or null while it is not loaded. Remembers
     * [options] for next time. Images are downloaded with the app's credentials and embedded.
     */
    suspend fun printHtml(options: PrintOptions, labels: PrintLabels): String? {
        val state = uiState.value as? ProjectDetailUiState.Loaded ?: return null
        appPreferences.setPrintChoices(options.content.name, options.includeImages)
        val project = state.detail
        val embedded =
            printImageLoader.embed(
                ProjectPrintDocument.imageUrls(project, options, ::resolveMediaUrl)
            )
        return ProjectPrintDocument.html(
            project = project,
            linkedYarnNames = state.linkedYarns.map { it.name },
            options = options,
            labels = labels,
            resolve = ::resolveMediaUrl,
            embeddedImages = embedded,
        )
    }

    /** Refreshes this project and only the yarns currently linked from its latest detail. */
    fun refresh() {
        history.reloadIfLoaded()
        refreshController.refresh {
            val project = projectRepository.refreshOne(projectId)
            var changed = project.changed
            project.detail.yarnIds.distinct().forEach { yarnId ->
                changed = yarnRepository.refreshOne(yarnId).changed || changed
            }
            changed
        }
    }

    /** The web URL for this project (SNA-17), for the detail screen's share action. */
    fun shareUrl(): String? = mediaUrlResolver.resolve("/projects/$projectId")

    fun toggleFavorite() {
        val state = uiState.value
        if (state !is ProjectDetailUiState.Loaded) return
        val wasFavorite = state.entity.isFavorite
        viewModelScope.launch {
            try {
                projectRepository.toggleFavorite(state.entity, wasFavorite = wasFavorite)
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

    /** "Mark as done" / "Mark as in progress" from the detail menu (needs edit access). */
    fun toggleStatus() {
        val state = uiState.value
        if (state !is ProjectDetailUiState.Loaded) return
        val next =
            if (state.entity.status == ProjectStatus.DONE) ProjectStatus.IN_PROGRESS
            else ProjectStatus.DONE
        viewModelScope.launch {
            try {
                projectRepository.setStatus(state.entity, next)
            } catch (e: Exception) {
                mutationFeedback.show(
                    if (e.isOfflineFailure()) R.string.mutation_favorite_offline
                    else R.string.error_status_failed
                )
            }
        }
    }

    fun delete() {
        if (uiState.value !is ProjectDetailUiState.Loaded) return
        viewModelScope.launch {
            try {
                projectRepository.deleteProject(projectId)
                syncScheduler.replayThenSyncNow()
                mutationFeedback.show(R.string.mutation_project_deleted_queued)
                _deleted.value = true
            } catch (e: Exception) {
                mutationFeedback.show(R.string.error_delete_failed)
            }
        }
    }

    fun deleteAttachment(attachmentId: Int) {
        if (uiState.value !is ProjectDetailUiState.Loaded) return
        viewModelScope.launch {
            try {
                projectRepository.queueAttachmentDelete(projectId, attachmentId)
                syncScheduler.replayThenSyncNow()
                mutationFeedback.show(R.string.mutation_project_attachment_deleted_queued)
            } catch (e: Exception) {
                mutationFeedback.show(R.string.error_attachment_delete_failed)
            }
        }
    }

    fun dismissRefreshFeedback() = refreshController.clearFeedback()

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val MAX_TIMER_SECONDS = 315_576_000L
    }
}
