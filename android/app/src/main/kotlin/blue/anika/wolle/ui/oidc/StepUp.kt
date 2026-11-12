package blue.anika.wolle.ui.oidc

import blue.anika.wolle.data.oidc.OidcCoordinator
import blue.anika.wolle.data.oidc.OidcEvent
import blue.anika.wolle.data.oidc.OidcFailure
import blue.anika.wolle.data.oidc.OidcFlowType
import blue.anika.wolle.data.oidc.OidcProvider
import blue.anika.wolle.data.oidc.ReauthAction
import blue.anika.wolle.data.oidc.ReauthProof
import blue.anika.wolle.data.oidc.ReauthTarget
import blue.anika.wolle.data.oidc.oidcFailure
import blue.anika.wolle.data.passkey.PasskeyFailure
import blue.anika.wolle.data.passkey.PasskeyHost
import blue.anika.wolle.data.passkey.PasskeyService
import blue.anika.wolle.data.passkey.passkeyFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the user is asked to confirm, and the ways they can do it. */
data class ReauthRequest(
    val action: ReauthAction,
    val target: ReauthTarget,
    val canUsePassword: Boolean,
    /** Offer "confirm with [OidcProvider.name] instead" (only if the account already has it). */
    val oidcProvider: OidcProvider?,
    /** Offer "confirm with a passkey" (the account has one and the server can verify it). */
    val canUsePasskey: Boolean = false,
)

/** The confirm sheet. [error] is shown inline; the password itself is never kept here. */
data class ReauthUiState(
    val request: ReauthRequest,
    val busy: Boolean = false,
    val error: OidcFailure? = null,
    /** Why confirming with a passkey did not work; a dismissed system sheet leaves this null. */
    val passkeyError: PasskeyFailure? = null,
)

/**
 * The step-up ("confirm it's you") part shared by linking, unlinking and the administrator's
 * unlink: collects a password or runs a provider reauthentication, then hands the one-use proof to
 * [onProof]. The proof lives only in memory, in the call to [onProof]; the password only in the
 * single [submitPassword] call.
 */
class StepUp(
    private val oidc: OidcCoordinator,
    private val scope: CoroutineScope,
    private val onProof: suspend (ReauthProof) -> Unit,
    private val onFailure: (OidcFailure) -> Unit,
    private val passkeys: PasskeyService? = null,
) {
    private val _sheet = MutableStateFlow<ReauthUiState?>(null)

    /** The open confirm sheet, or null. */
    val sheet: StateFlow<ReauthUiState?> = _sheet.asStateFlow()

    /** Set while the user is in the browser confirming with the provider. */
    private var awaiting: ReauthRequest? = null

    fun begin(request: ReauthRequest) {
        awaiting = null
        _sheet.value = ReauthUiState(request)
    }

    fun dismiss() {
        _sheet.value = null
    }

    fun submitPassword(password: String) {
        val current = _sheet.value ?: return
        if (current.busy || password.isEmpty()) return
        _sheet.value = current.copy(busy = true, error = null)
        scope.launch {
            val result =
                oidc.reauthWithPassword(password, current.request.action, current.request.target)
            val proof = result.getOrNull()
            if (proof == null) {
                _sheet.value = current.copy(busy = false, error = result.oidcFailure())
            } else {
                _sheet.value = null
                onProof(proof)
            }
        }
    }

    /** Confirms with one of the account's passkeys; [host] is where the system sheet appears. */
    fun usePasskey(host: PasskeyHost) {
        val current = _sheet.value ?: return
        val service = passkeys ?: return
        if (current.busy || !current.request.canUsePasskey) return
        _sheet.value = current.copy(busy = true, error = null, passkeyError = null)
        scope.launch {
            val result = service.reauth(host, current.request.action, current.request.target)
            val proof = result.getOrNull()
            if (proof == null) {
                val failure = result.passkeyFailure()
                _sheet.value =
                    current.copy(
                        busy = false,
                        passkeyError = failure.takeUnless { it == PasskeyFailure.Cancelled },
                    )
            } else {
                _sheet.value = null
                onProof(proof)
            }
        }
    }

    fun useProvider() {
        val current = _sheet.value ?: return
        val provider = current.request.oidcProvider ?: return
        if (current.busy) return
        _sheet.value = current.copy(busy = true, error = null)
        scope.launch {
            val failure =
                oidc.startReauth(provider.id, current.request.action, current.request.target)
            if (failure == null) {
                awaiting = current.request
                _sheet.value = null
            } else {
                _sheet.value = current.copy(busy = false, error = failure)
            }
        }
    }

    /** Offers an [OidcCoordinator] result; returns true when it was ours (and consumed). */
    suspend fun handle(event: OidcEvent): Boolean {
        val waiting = awaiting ?: return false
        when (event) {
            is OidcEvent.Reauthenticated -> {
                if (event.proof.action != waiting.action || event.proof.target != waiting.target) {
                    return false
                }
                awaiting = null
                oidc.consume(event)
                onProof(event.proof)
                return true
            }
            is OidcEvent.Failed -> {
                if (event.flow != OidcFlowType.REAUTH) return false
                awaiting = null
                oidc.consume(event)
                onFailure(event.failure)
                return true
            }
            else -> return false
        }
    }
}
