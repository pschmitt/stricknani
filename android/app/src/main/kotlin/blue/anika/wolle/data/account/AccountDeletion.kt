package blue.anika.wolle.data.account

import blue.anika.wolle.data.oidc.ReauthAction
import blue.anika.wolle.data.oidc.ReauthProof
import blue.anika.wolle.ui.common.isOfflineFailure
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * `POST /api/v2/account/delete` (`docs/web-v2-contract.md`): permanently deletes the account and
 * all its data. 204 on success; needs a one-use `delete_account` proof in `X-Reauth-Token` (403
 * `reauth_required` without it) and the typed confirmation. 409 `last_admin` when this is the only
 * administrator. Servers without it answer 404/405.
 */
interface AccountDeletionApi {
    @POST("api/v2/account/delete")
    suspend fun deleteAccount(
        @Header("X-Reauth-Token") reauthToken: String,
        @Body request: DeleteAccountRequest,
    )
}

@Serializable data class DeleteAccountRequest(val confirmation: String)

/** The answer to `POST /api/v2/account/delete`. */
sealed interface DeleteAccountResult {
    /** 204: the account and its data are gone; the app must wipe its local copy. */
    data object Deleted : DeleteAccountResult

    /** 403 `reauth_required`: the proof was missing, used up or expired. */
    data object ReauthRequired : DeleteAccountResult

    /** 422 on `confirmation`: neither `DELETE` nor the account email. */
    data object ConfirmationRejected : DeleteAccountResult

    /** 409 `last_admin`: the only administrator cannot delete their account. */
    data object LastAdministrator : DeleteAccountResult

    data object RateLimited : DeleteAccountResult

    /** 401: the token is no longer valid (nothing was deleted). */
    data object SessionExpired : DeleteAccountResult

    /** An older server without self-service deletion (404/405). */
    data object Unsupported : DeleteAccountResult

    data class Failed(val offline: Boolean) : DeleteAccountResult
}

/** What the delete-account UI needs from the server; faked in tests. */
interface AccountDeletionGateway {
    /** [proof] must be a `delete_account` proof; it is sent once and never kept. */
    suspend fun deleteAccount(confirmation: String, proof: ReauthProof): DeleteAccountResult
}

@Singleton
class AccountDeletionRepository @Inject constructor(private val api: AccountDeletionApi) :
    AccountDeletionGateway {
    override suspend fun deleteAccount(
        confirmation: String,
        proof: ReauthProof,
    ): DeleteAccountResult {
        if (proof.action != ReauthAction.DELETE_ACCOUNT) return DeleteAccountResult.ReauthRequired
        return try {
            api.deleteAccount(proof.token, DeleteAccountRequest(confirmation.trim()))
            DeleteAccountResult.Deleted
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            val http = failure.httpFailure()
            when {
                http == null -> DeleteAccountResult.Failed(failure.isOfflineFailure())
                http.status == 403 && http.code == "reauth_required" ->
                    DeleteAccountResult.ReauthRequired
                http.status == 409 && http.code == "last_admin" ->
                    DeleteAccountResult.LastAdministrator
                http.status == 422 && "confirmation" in http.fields ->
                    DeleteAccountResult.ConfirmationRejected
                http.status == 429 -> DeleteAccountResult.RateLimited
                http.status == 401 && http.code == "session_expired" ->
                    DeleteAccountResult.SessionExpired
                http.status == 404 || http.status == 405 -> DeleteAccountResult.Unsupported
                else -> DeleteAccountResult.Failed(offline = false)
            }
        }
    }
}

/** Whether [input] is the literal `DELETE` or [email] (any case): what the server will accept. */
fun deletionConfirmationMatches(input: String, email: String): Boolean {
    val typed = input.trim()
    return typed.isNotEmpty() &&
        (typed == "DELETE" || (email.isNotBlank() && typed.equals(email.trim(), ignoreCase = true)))
}
