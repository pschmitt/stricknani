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
)

/** The confirm sheet. [error] is shown inline; the password itself is never kept here. */
data class ReauthUiState(
    val request: ReauthRequest,
    val busy: Boolean = false,
    val error: OidcFailure? = null,
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
