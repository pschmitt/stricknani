package blue.anika.wolle.ui.passkey

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blue.anika.wolle.R
import blue.anika.wolle.data.device.DeviceNameProvider
import blue.anika.wolle.data.oidc.OidcCoordinator
import blue.anika.wolle.data.oidc.ReauthAction
import blue.anika.wolle.data.oidc.ReauthProof
import blue.anika.wolle.data.oidc.ReauthTarget
import blue.anika.wolle.data.passkey.DEFAULT_PASSKEY_LIMIT
import blue.anika.wolle.data.passkey.PasskeyDto
import blue.anika.wolle.data.passkey.PasskeyFailure
import blue.anika.wolle.data.passkey.PasskeyHost
import blue.anika.wolle.data.passkey.PasskeyService
import blue.anika.wolle.data.passkey.passkeyFailure
import blue.anika.wolle.ui.common.DangerousActionGuard
import blue.anika.wolle.ui.common.MutationFeedback
import blue.anika.wolle.ui.oidc.ReauthRequest
import blue.anika.wolle.ui.oidc.ReauthUiState
import blue.anika.wolle.ui.oidc.StepUp
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface PasskeysPhase {
    /** Not known yet, or the server has no passkeys: the card is not shown at all. */
    data object Hidden : PasskeysPhase

    data object Ready : PasskeysPhase

    data object Failed : PasskeysPhase
}

data class PasskeysState(
    val phase: PasskeysPhase = PasskeysPhase.Hidden,
    val passkeys: List<PasskeyDto> = emptyList(),
    val limit: Int = DEFAULT_PASSKEY_LIMIT,
    val passwordEnabled: Boolean = true,
    /** A request (list, rename, remove, register) is in flight. */
    val working: Boolean = false,
    /** The passkey being renamed (its name field is open). */
    val renaming: PasskeyDto? = null,
    /** The passkey the person is asked to confirm removing. */
    val confirmRemove: PasskeyDto? = null,
    /**
     * The confirmation for adding a passkey is done; the screen answers with
     * [PasskeysViewModel.completeAdd], handing in the window the system sheet needs.
     */
    val addReady: Boolean = false,
) {
    val canAdd: Boolean
        get() = passkeys.size < limit
}

/** What to do once the confirm step produced a proof. */
private sealed interface AfterProof {
    data object Add : AfterProof

    data class Remove(val passkey: PasskeyDto) : AfterProof
}

/**
 * Settings → Account → Passkeys: list, add (through Credential Manager), rename and remove. Adding
 * and removing ask for a fresh confirmation (password or a passkey), like the web app. Hidden when
 * the server has no passkey routes or has them switched off.
 *
 * The view model never holds an Activity: the screen passes a [PasskeyHost] only into
 * [completeAdd], the one call that needs a window.
 */
@HiltViewModel
class PasskeysViewModel
internal constructor(
    private val passkeys: PasskeyService,
    private val oidc: OidcCoordinator,
    private val feedback: MutationFeedback,
    private val guard: DangerousActionGuard,
    /** What a new passkey is called until its owner renames it: this phone's name. */
    private val newPasskeyName: () -> String,
) : ViewModel() {
    @Inject
    constructor(
        passkeys: PasskeyService,
        oidc: OidcCoordinator,
        feedback: MutationFeedback,
        guard: DangerousActionGuard,
        deviceNames: DeviceNameProvider,
    ) : this(passkeys, oidc, feedback, guard, { deviceNames.deviceName() })

    private val _state = MutableStateFlow(PasskeysState())
    val state: StateFlow<PasskeysState> = _state.asStateFlow()

    private var afterProof: AfterProof? = null
    private var addProof: ReauthProof? = null

    private val stepUp =
        StepUp(
            oidc,
            viewModelScope,
            onProof = ::proceed,
            onFailure = { /* provider confirmations are not offered here */ },
            passkeys = passkeys,
        )
    val sheet: StateFlow<ReauthUiState?> = stepUp.sheet

    init {
        load()
    }

    fun reload() = load()

    private fun load() {
        viewModelScope.launch {
            val result = passkeys.list()
            val list = result.getOrNull()
            if (list == null) {
                val failure = result.passkeyFailure()
                _state.update {
                    // A server without passkeys is not an error: the card just stays away.
                    if (failure == PasskeyFailure.Unavailable) it.copy(phase = PasskeysPhase.Hidden)
                    else it.copy(phase = PasskeysPhase.Failed)
                }
                return@launch
            }
            val passwordEnabled =
                oidc.loadIdentities().getOrNull()?.meta?.passwordEnabled
                    ?: _state.value.passwordEnabled
            _state.update {
                it.copy(
                    phase = PasskeysPhase.Ready,
                    passkeys = list.passkeys,
                    limit = list.limit,
                    passwordEnabled = passwordEnabled,
                )
            }
        }
    }

    // ---- user actions ----------------------------------------------------------------------

    fun onAddClicked() {
        if (_state.value.working || !_state.value.canAdd) return
        afterProof = AfterProof.Add
        stepUp.begin(request())
    }

    fun onRemoveClicked(passkey: PasskeyDto) {
        if (_state.value.working) return
        _state.update { it.copy(confirmRemove = passkey) }
    }

    fun dismissRemove() = _state.update { it.copy(confirmRemove = null) }

    fun confirmRemove() {
        val passkey = _state.value.confirmRemove ?: return
        _state.update { it.copy(confirmRemove = null) }
        // After the dialog, before the confirmation: a refused unlock does nothing.
        guard.confirm(R.string.unlock_reason_remove_passkey) {
            afterProof = AfterProof.Remove(passkey)
            stepUp.begin(request())
        }
    }

    fun onRenameClicked(passkey: PasskeyDto) = _state.update { it.copy(renaming = passkey) }

    fun dismissRename() = _state.update { it.copy(renaming = null) }

    fun rename(name: String) {
        val passkey = _state.value.renaming ?: return
        val clean = name.trim()
        if (clean.isEmpty() || clean.length > MAX_NAME_CHARS || _state.value.working) return
        _state.update { it.copy(renaming = null, working = true) }
        viewModelScope.launch {
            val result = passkeys.rename(passkey.id, clean)
            val updated = result.getOrNull()
            _state.update { current ->
                current.copy(
                    working = false,
                    passkeys =
                        if (updated == null) current.passkeys
                        else current.passkeys.map { if (it.id == updated.id) updated else it },
                )
            }
            if (updated != null) feedback.show(R.string.passkey_renamed_done)
            else result.passkeyFailure()?.let(feedback::showPasskeyFailure)
        }
    }

    fun dismissSheet() {
        afterProof = null
        stepUp.dismiss()
    }

    fun submitPassword(password: String) = stepUp.submitPassword(password)

    fun confirmWithPasskey(host: PasskeyHost) = stepUp.usePasskey(host)

    /** The confirmation for adding is done: create the passkey through the system sheet. */
    fun completeAdd(host: PasskeyHost) {
        val proof = addProof ?: return
        addProof = null
        _state.update { it.copy(addReady = false, working = true) }
        viewModelScope.launch {
            val result = passkeys.register(host, newPasskeyName(), proof)
            val created = result.getOrNull()
            _state.update { current ->
                current.copy(
                    working = false,
                    passkeys =
                        if (created == null) current.passkeys else current.passkeys + created,
                    // The new passkey is named after this phone; offer to rename it right away.
                    renaming = created ?: current.renaming,
                )
            }
            if (created != null) feedback.show(R.string.passkey_added_done)
            else result.passkeyFailure()?.let(feedback::showPasskeyFailure)
        }
    }

    // ---- internals -------------------------------------------------------------------------

    private fun request(): ReauthRequest {
        val current = _state.value
        return ReauthRequest(
            action = ReauthAction.MANAGE_PASSKEYS,
            target = ReauthTarget.None,
            canUsePassword = current.passwordEnabled,
            oidcProvider = null,
        )
    }

    private suspend fun proceed(proof: ReauthProof) {
        when (val next = afterProof) {
            AfterProof.Add -> {
                afterProof = null
                addProof = proof
                _state.update { it.copy(addReady = true) }
            }
            is AfterProof.Remove -> {
                afterProof = null
                _state.update { it.copy(working = true) }
                val result = passkeys.delete(next.passkey.id, proof)
                val ok = result.isSuccess
                _state.update { current ->
                    current.copy(
                        working = false,
                        passkeys =
                            if (ok) current.passkeys.filterNot { it.id == next.passkey.id }
                            else current.passkeys,
                    )
                }
                if (ok) feedback.show(R.string.passkey_removed_done)
                else result.passkeyFailure()?.let(feedback::showPasskeyFailure)
            }
            null -> Unit
        }
    }

    companion object {
        /** Same limit as the server and the web app. */
        const val MAX_NAME_CHARS = 100
    }
}
