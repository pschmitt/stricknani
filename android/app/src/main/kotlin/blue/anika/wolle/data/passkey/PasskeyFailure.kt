package blue.anika.wolle.data.passkey

import blue.anika.wolle.data.api.dto.ApiErrorEnvelopeDto
import java.io.IOException
import kotlinx.serialization.json.Json
import retrofit2.HttpException

/**
 * Why a passkey step did not work, in terms the screens can explain. No variant carries server text
 * or credential material.
 */
sealed interface PasskeyFailure {
    /** The person dismissed the system sheet (or it timed out). Not an error to shout about. */
    data object Cancelled : PasskeyFailure

    /** Sign-in: this device holds no passkey for this server. */
    data object NoCredential : PasskeyFailure

    /** Registration: this device already holds a passkey for the account. */
    data object AlreadyRegistered : PasskeyFailure

    /** No credential provider can do it (old Android, no Google Play services, none set up). */
    data object Unsupported : PasskeyFailure

    /**
     * 401 `invalid_passkey`: unknown credential, bad signature, wrong origin or disabled account.
     */
    data object NotRecognized : PasskeyFailure

    /** 400 `invalid_challenge`: the request expired or was used already. */
    data object Expired : PasskeyFailure

    /** 403 `reauth_required`: the confirmation is missing, used up or too old. */
    data object ReauthRequired : PasskeyFailure

    /** 409 `passkey_limit`. */
    data object LimitReached : PasskeyFailure

    /** 409 `no_passkeys`: a confirmation with a passkey, but the account has none. */
    data object NoPasskeys : PasskeyFailure

    /** 404 `passkeys_unavailable` (or a server without the routes). */
    data object Unavailable : PasskeyFailure

    /** 422 or 409 on registration: the server did not accept the new passkey. */
    data object Rejected : PasskeyFailure

    data class RateLimited(val retryAfterSeconds: Long?) : PasskeyFailure

    /** 401 `session_expired`: the current sign-in is no longer valid. */
    data object SessionExpired : PasskeyFailure

    data object Offline : PasskeyFailure

    data class Other(val status: Int?) : PasskeyFailure
}

/** Carries an already classified [PasskeyFailure] through code that only knows exceptions. */
class PasskeyException(val failure: PasskeyFailure) : Exception(failure.toString())

/** A non-2xx answer from the unauthenticated (OkHttp) login client. */
class PasskeyHttpException(val status: Int, val code: String?, val retryAfterSeconds: Long?) :
    Exception("HTTP $status")

fun passkeyFailureFor(
    status: Int,
    code: String?,
    retryAfterSeconds: Long? = null,
): PasskeyFailure =
    when {
        status == 429 || code == "rate_limited" -> PasskeyFailure.RateLimited(retryAfterSeconds)
        code == "invalid_passkey" -> PasskeyFailure.NotRecognized
        code == "invalid_challenge" -> PasskeyFailure.Expired
        code == "reauth_required" -> PasskeyFailure.ReauthRequired
        code == "passkey_limit" -> PasskeyFailure.LimitReached
        code == "no_passkeys" -> PasskeyFailure.NoPasskeys
        code == "passkey_rejected" || code == "passkey_exists" -> PasskeyFailure.Rejected
        code == "passkeys_unavailable" -> PasskeyFailure.Unavailable
        code == "session_expired" -> PasskeyFailure.SessionExpired
        // A server without the routes answers 404 with no (or another) error code.
        status == 404 || status == 405 -> PasskeyFailure.Unavailable
        else -> PasskeyFailure.Other(status)
    }

private val ERROR_JSON = Json { ignoreUnknownKeys = true }

/** Turns any thrown request error into a [PasskeyFailure]. Pure, so it is unit-tested. */
fun Throwable.toPasskeyFailure(): PasskeyFailure {
    var current: Throwable? = this
    while (current != null) {
        when (current) {
            is PasskeyException -> return current.failure
            is PasskeyHttpException ->
                return passkeyFailureFor(current.status, current.code, current.retryAfterSeconds)
            is HttpException -> {
                val code =
                    runCatching {
                            ERROR_JSON.decodeFromString<ApiErrorEnvelopeDto>(
                                    current.response()?.errorBody()?.string().orEmpty()
                                )
                                .error
                                ?.code
                        }
                        .getOrNull()
                val retryAfter =
                    current.response()?.headers()?.get("Retry-After")?.trim()?.toLongOrNull()
                return passkeyFailureFor(current.code(), code, retryAfter)
            }
            is IOException -> return PasskeyFailure.Offline
        }
        current = current.cause
    }
    return PasskeyFailure.Other(null)
}

/** The [PasskeyFailure] of a failed [Result], null on success. */
fun Result<*>.passkeyFailure(): PasskeyFailure? = exceptionOrNull()?.toPasskeyFailure()
