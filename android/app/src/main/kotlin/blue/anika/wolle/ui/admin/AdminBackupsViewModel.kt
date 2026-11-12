package blue.anika.wolle.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blue.anika.wolle.data.backups.BackupEntryDto
import blue.anika.wolle.data.backups.BackupListDto
import blue.anika.wolle.data.backups.BackupsGateway
import blue.anika.wolle.data.backups.DataEpochGuard
import blue.anika.wolle.data.backups.MediaOption
import blue.anika.wolle.data.backups.RestartProbe
import blue.anika.wolle.data.backups.RestartResult
import blue.anika.wolle.data.backups.RestoreErrorKind
import blue.anika.wolle.data.backups.RestoreEvent
import blue.anika.wolle.data.backups.RestoreState
import blue.anika.wolle.data.backups.backupOrder
import blue.anika.wolle.data.backups.canRestore
import blue.anika.wolle.data.backups.reduceRestore
import blue.anika.wolle.data.backups.toBackupsException
import blue.anika.wolle.data.backups.waitForRestart
import blue.anika.wolle.data.oidc.OidcCoordinator
import blue.anika.wolle.data.oidc.OidcEvent
import blue.anika.wolle.data.oidc.OidcIdentity
import blue.anika.wolle.data.oidc.OidcProvider
import blue.anika.wolle.data.oidc.ReauthAction
import blue.anika.wolle.data.oidc.ReauthTarget
import blue.anika.wolle.data.repository.AdminFailure
import blue.anika.wolle.data.settings.SettingsRepository
import blue.anika.wolle.ui.common.MutationFeedback
import blue.anika.wolle.ui.oidc.DEFAULT_PROVIDER_NAME
import blue.anika.wolle.ui.oidc.ReauthRequest
import blue.anika.wolle.ui.oidc.ReauthUiState
import blue.anika.wolle.ui.oidc.StepUp
import blue.anika.wolle.ui.oidc.showOidcFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The listing of the Backups screen. */
sealed interface BackupsPhase {
    data object Loading : BackupsPhase

    data class Failed(val failure: AdminFailure) : BackupsPhase

    data class Ready(val list: BackupListDto, val backups: List<BackupEntryDto>) : BackupsPhase
}

/**
 * Settings -> Admin -> Backups: the listing and the guarded restore. Web counterpart:
 * `AdminBackups.svelte` + `RestoreBackupDialog.svelte`; the state machine is in `RestoreFlow.kt`.
 */
@HiltViewModel
class AdminBackupsViewModel
@Inject
constructor(
    private val gateway: BackupsGateway,
    private val oidc: OidcCoordinator,
    private val settings: SettingsRepository,
    private val probe: RestartProbe,
    private val epochGuard: DataEpochGuard,
    private val feedback: MutationFeedback,
) : ViewModel() {
    private val _phase = MutableStateFlow<BackupsPhase>(BackupsPhase.Loading)
    val phase: StateFlow<BackupsPhase> = _phase.asStateFlow()

    private val _flow = MutableStateFlow<RestoreState>(RestoreState.Closed)
    val flow: StateFlow<RestoreState> = _flow.asStateFlow()

    private var providers: List<OidcProvider> = emptyList()
    private var identities: List<OidcIdentity> = emptyList()
    private var passwordEnabled = true
    private var loadGeneration = 0

    private val stepUp =
        StepUp(
            oidc,
            viewModelScope,
            onProof = { proof -> dispatch(RestoreEvent.ReauthConfirmed(now(), proof)) },
            onFailure = { failure ->
                dispatch(RestoreEvent.ReauthCancelled)
                feedback.showOidcFailure(
                    failure,
                    providers.firstOrNull()?.name ?: DEFAULT_PROVIDER_NAME,
                )
            },
        )
    val sheet: StateFlow<ReauthUiState?> = stepUp.sheet

    init {
        viewModelScope.launch { oidc.events.collect { event -> event?.let { onEvent(it) } } }
        load()
        loadSignInMethods()
    }

    fun retry() = load()

    private fun load() {
        val generation = ++loadGeneration
        _phase.value = BackupsPhase.Loading
        viewModelScope.launch {
            val result = runCatching { gateway.list() }
            if (generation != loadGeneration) return@launch
            _phase.value =
                result.fold(
                    onSuccess = { BackupsPhase.Ready(it, it.backups.sortedWith(backupOrder)) },
                    onFailure = { BackupsPhase.Failed(adminFailureOf(it)) },
                )
        }
    }

    private fun adminFailureOf(failure: Throwable): AdminFailure {
        val error = failure.toBackupsException()
        return when {
            error.notAllowed -> AdminFailure.NotAllowed
            error.kind == RestoreErrorKind.OFFLINE -> AdminFailure.Offline
            else -> AdminFailure.Other
        }
    }

    // --- the restore flow ---

    private fun now(): Long = System.currentTimeMillis()

    private fun dispatch(event: RestoreEvent) = _flow.update { reduceRestore(it, event) }

    /** Opens the flow for [entry] (a no-op for a backup that cannot be restored). */
    fun open(entry: BackupEntryDto) {
        dispatch(RestoreEvent.Open(entry, null))
        val loading = _flow.value as? RestoreState.Loading ?: return
        viewModelScope.launch {
            try {
                dispatch(RestoreEvent.PreviewLoaded(gateway.preview(loading.backup.name)))
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                dispatch(RestoreEvent.PreviewFailed(failure.toBackupsException().kind))
            }
        }
    }

    fun setMedia(media: MediaOption) = dispatch(RestoreEvent.SetMedia(media))

    fun proceed() {
        dispatch(RestoreEvent.Continue(now()))
        beginStepUpIfNeeded()
    }

    fun onTyped(value: String) = dispatch(RestoreEvent.Typed(value))

    fun back() = dispatch(RestoreEvent.Back)

    fun close() = dispatch(RestoreEvent.Close)

    /** The sheet was dismissed without confirming. */
    fun dismissSheet() {
        stepUp.dismiss()
        dispatch(RestoreEvent.ReauthCancelled)
    }

    fun submitPassword(password: String) = stepUp.submitPassword(password)

    fun confirmWithProvider() = stepUp.useProvider()

    private fun beginStepUpIfNeeded() {
        if (_flow.value !is RestoreState.Reauth) return
        stepUp.begin(
            ReauthRequest(
                action = ReauthAction.RESTORE_BACKUP,
                target = ReauthTarget.None,
                canUsePassword = passwordEnabled,
                // Provider confirmation needs a login the account already has.
                oidcProvider =
                    identities.firstNotNullOfOrNull { id ->
                        providers.firstOrNull { it.id == id.provider }
                    },
            )
        )
    }

    fun submit() {
        val state = _flow.value as? RestoreState.Confirm ?: return
        if (!state.canRestore()) return
        val proof = state.grant.proof
        dispatch(RestoreEvent.Submit)
        if (_flow.value !is RestoreState.Restoring) return
        viewModelScope.launch {
            try {
                val accepted = gateway.restore(state.backup.name, state.typed, state.media, proof)
                val requestedAt = now()
                dispatch(RestoreEvent.Accepted(accepted, requestedAt))
                if (accepted.restarting) follow(requestedAt)
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                val error = failure.toBackupsException()
                dispatch(RestoreEvent.Rejected(error.kind, error.reauthRequired))
                beginStepUpIfNeeded()
            }
        }
    }

    /** Polls the restarting server until it says what happened. */
    private suspend fun follow(requestedAtMs: Long) {
        val previousEpoch = (_phase.value as? BackupsPhase.Ready)?.list?.dataEpoch
        val result = waitForRestart(probe, previousEpoch, requestedAtMs)
        when (result) {
            RestartResult.FAILED -> {
                // The original data is back, so the session still works: ask why.
                val reason = runCatching { gateway.list().lastRestore?.error }.getOrNull()
                dispatch(RestoreEvent.ServerBack(result, reason))
            }
            RestartResult.SUCCEEDED -> {
                // The data is a different one now: drop the local copy before anything shows it.
                runCatching { epochGuard.check() }
                dispatch(RestoreEvent.ServerBack(result))
            }
            RestartResult.TIMEOUT -> dispatch(RestoreEvent.ServerBack(result))
        }
    }

    /** "Check again" after a timeout: the server may have come back meanwhile. */
    fun checkAgain() {
        val timeout = _flow.value as? RestoreState.Timeout ?: return
        _flow.value = RestoreState.Waiting(timeout.backup, timeout.accepted, now())
        viewModelScope.launch { follow(now() - 60_000) }
    }

    /** The result dialog was closed: reload the listing (the data may be different now). */
    fun finish() {
        dispatch(RestoreEvent.Close)
        load()
    }

    // --- sign-in methods for the re-authentication sheet ---

    private fun loadSignInMethods() {
        viewModelScope.launch {
            providers =
                runCatching { oidc.providers(settings.credentials.value.serverUrl) }
                    .getOrDefault(emptyList())
            if (providers.isEmpty()) return@launch
            val envelope = oidc.loadIdentities().getOrNull()
            identities = envelope?.data.orEmpty()
            passwordEnabled = envelope?.meta?.passwordEnabled ?: true
        }
    }

    private suspend fun onEvent(event: OidcEvent) {
        stepUp.handle(event)
    }
}
