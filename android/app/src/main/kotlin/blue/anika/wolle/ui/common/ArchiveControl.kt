package blue.anika.wolle.ui.common

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import blue.anika.wolle.R
import blue.anika.wolle.data.api.ApiCapabilities
import blue.anika.wolle.data.api.v2.ArchiveRequestBody
import blue.anika.wolle.data.api.v2.ArchiveStatusDto
import blue.anika.wolle.data.api.v2.ArchiveV2Api
import blue.anika.wolle.data.oidc.errorCodeOf
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException

/** Which kind of item an [ArchiveControl] archives: the path segment of the archive route. */
enum class ArchiveEntity(val path: String) {
    PROJECT("projects"),
    YARN("yarns"),
}

/** One archive control's state; [state] is the server's job state (null: never archived). */
data class ArchiveUiState(
    val available: Boolean = false,
    val loaded: Boolean = false,
    val state: String? = null,
    val busy: Boolean = false,
    val pollingExhausted: Boolean = false,
    @StringRes val error: Int? = null,
) {
    val active: Boolean
        get() = state == "pending" || state == "running"
}

/**
 * Drives the web's archive control (`ArchiveControl.svelte`) for one project or yarn: reads the
 * owner's current archive job, requests a new one, and polls while it runs (the web's delays, at
 * most ten checks over two minutes, only while the screen is shown). When a job finishes as
 * `archived`, [archivedEvents] ticks so the detail screen reloads and shows "Open archived copy".
 */
@HiltViewModel
class ArchiveViewModel
@Inject
constructor(private val api: ArchiveV2Api, private val capabilities: ApiCapabilities) :
    ViewModel() {
    private val _state = MutableStateFlow(ArchiveUiState())
    val state: StateFlow<ArchiveUiState> = _state.asStateFlow()
    private val _archivedEvents = MutableStateFlow(0)
    val archivedEvents: StateFlow<Int> = _archivedEvents.asStateFlow()

    private var target: Pair<ArchiveEntity, Int>? = null
    private var sourceLink: String? = null
    private var polling: Job? = null

    /** Loads the status for [entity] [id]; a changed [link] reloads it (the job may be stale). */
    fun bind(entity: ArchiveEntity, id: Int, link: String) {
        if (target == entity to id && sourceLink == link) return
        target = entity to id
        sourceLink = link
        polling?.cancel()
        _state.value = ArchiveUiState()
        viewModelScope.launch {
            val available = runCatching { capabilities.resolve().archive }.getOrDefault(false)
            _state.update { it.copy(available = available) }
            if (available) refreshStatus(startPolling = true)
        }
    }

    fun request() {
        val (entity, id) = target ?: return
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                apply(api.request(entity.path, id, ArchiveRequestBody()).data)
                pollWhileActive()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                _state.update { it.copy(error = errorText(failure)) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /** "Check archive status again" and "Try again": one more read, then polling if it runs. */
    fun checkAgain() {
        viewModelScope.launch { refreshStatus(startPolling = true) }
    }

    private suspend fun refreshStatus(startPolling: Boolean) {
        val (entity, id) = target ?: return
        _state.update { it.copy(busy = true, error = null) }
        try {
            apply(api.status(entity.path, id).data)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            _state.update { it.copy(error = errorText(failure)) }
        } finally {
            _state.update { it.copy(busy = false, loaded = true) }
        }
        if (startPolling && _state.value.active) pollWhileActive()
    }

    private fun pollWhileActive() {
        polling?.cancel()
        _state.update { it.copy(pollingExhausted = false) }
        polling = viewModelScope.launch {
            val (entity, id) = target ?: return@launch
            for (pause in POLL_DELAYS_MS) {
                delay(pause)
                try {
                    apply(api.status(entity.path, id).data)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (failure: Exception) {
                    _state.update { it.copy(error = errorText(failure)) }
                    return@launch
                }
                if (!_state.value.active) return@launch
            }
            _state.update { it.copy(pollingExhausted = true) }
        }
    }

    private fun apply(status: ArchiveStatusDto) {
        val before = _state.value.state
        _state.update { it.copy(state = status.state, loaded = true) }
        // Only a job that finished while we watched; an already archived copy is in the detail.
        if (status.state == "archived" && (before == "pending" || before == "running")) {
            _archivedEvents.update { it + 1 }
        }
    }

    @StringRes
    private fun errorText(failure: Exception): Int {
        val code =
            (failure as? HttpException)?.let {
                runCatching { it.response()?.errorBody()?.string() }.getOrNull()?.let(::errorCodeOf)
            }
        return if (code == "rate_limited" || (failure as? HttpException)?.code() == 429)
            R.string.archive_rate_limited
        else R.string.archive_unreachable
    }

    companion object {
        /** The web's `ARCHIVE_POLL_DELAYS_MS`: ten checks, 119 seconds in all. */
        val POLL_DELAYS_MS =
            listOf(2_000L, 4_000L, 8_000L, 15_000L, 15_000L, 15_000L, 15_000L, 15_000L, 15_000L)
                .plus(15_000L)
    }
}

/**
 * The archive control under a source link, for the item's owner when the server can archive: the
 * job's status in words and "Archive this source" / "Request a new archive" (or "Check archive
 * status again" once polling gave up while the job still runs). [onArchived] reloads the item.
 */
/** What the archive control offers right now: a labelled action, or nothing. */
private data class ArchiveAction(@StringRes val label: Int, val run: () -> Unit)

@Composable
fun ArchiveControl(
    entity: ArchiveEntity,
    id: Int,
    sourceLink: String,
    onArchived: () -> Unit,
    modifier: Modifier = Modifier,
    archivedUrl: String? = null,
    archiveLabel: String = "",
) {
    val viewModel: ArchiveViewModel = hiltViewModel(key = "archive-${entity.path}-$id")
    LaunchedEffect(entity, id, sourceLink) { viewModel.bind(entity, id, sourceLink) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val archived by viewModel.archivedEvents.collectAsStateWithLifecycle()
    LaunchedEffect(archived) { if (archived > 0) onArchived() }
    if (!state.available) {
        archivedUrl?.let { SourceLinkRow(PhosphorIcons.ClockCounterClockwise, archiveLabel, it) }
        return
    }
    val action: ArchiveAction? =
        when {
            state.busy -> null
            state.error != null ->
                ArchiveAction(
                    if (state.loaded) R.string.archive_try_again else R.string.archive_check_again,
                    viewModel::checkAgain,
                )
            !state.loaded -> null
            state.active && state.pollingExhausted ->
                ArchiveAction(R.string.archive_check_again, viewModel::checkAgain)
            state.active -> null
            state.state != "archived" ->
                ArchiveAction(
                    if (state.state == "failed" || state.state == "superseded")
                        R.string.archive_request_new
                    else R.string.archive_request,
                    viewModel::request,
                )
            else -> null
        }
    @StringRes
    val progress: Int? =
        when {
            state.busy -> R.string.archive_checking
            !state.loaded -> R.string.archive_loading
            else -> null
        }
    Column(
        modifier = modifier.fillMaxWidth().padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (archivedUrl != null) {
            // The archived copy and its action on one line; the status below the link.
            SourceLinkRow(
                PhosphorIcons.ClockCounterClockwise,
                archiveLabel,
                archivedUrl,
                trailing =
                    action?.let { current ->
                        {
                            val label = stringResource(current.label)
                            IconButton(onClick = current.run) {
                                Icon(Icons.Filled.Refresh, contentDescription = label)
                            }
                        }
                    },
            )
            if (state.loaded && state.state != null && state.state != "archived") {
                Text(
                    stringResource(statusText(state.state)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier.padding(start = 48.dp).semantics {
                            liveRegion = LiveRegionMode.Polite
                        },
                )
            }
        } else {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(statusText(state.state)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                action?.let {
                    OutlinedButton(
                        onClick = it.run,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                    ) {
                        Text(stringResource(it.label))
                    }
                }
            }
        }
        progress?.let {
            Text(
                stringResource(it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.error?.let {
            Text(
                stringResource(it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@StringRes
private fun statusText(state: String?): Int =
    when (state) {
        "pending" -> R.string.archive_status_pending
        "running" -> R.string.archive_status_running
        "archived" -> R.string.archive_status_archived
        "failed" -> R.string.archive_status_failed
        "superseded" -> R.string.archive_status_superseded
        else -> R.string.archive_status_none
    }
