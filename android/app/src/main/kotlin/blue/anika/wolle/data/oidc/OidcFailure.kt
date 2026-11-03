package blue.anika.wolle.data.oidc

import blue.anika.wolle.data.api.dto.ApiErrorEnvelopeDto
import java.io.IOException
import kotlinx.serialization.json.Json
import retrofit2.HttpException

/**
 * Why an OIDC step did not work, in terms the screens can explain (error table of
 * `docs/oidc-v2-contract.md`). No variant carries server text, codes or credentials.
 */
sealed interface OidcFailure {
    /** The callback did not match a pending flow, or the server said `invalid_oidc_flow`. */
    data object Expired : OidcFailure

    /** 403 `oidc_identity_unlinked`: the provider login is not linked to a local account yet. */
    data object Unlinked : OidcFailure

    /** 503 `oidc_unavailable`, or 404 `oidc_disabled`: use the password instead. */
    data object Unavailable : OidcFailure

    /** 429: wait [retryAfterSeconds] (when the server said how long). */
    data class RateLimited(val retryAfterSeconds: Long?) : OidcFailure

    /** 409 `identity_already_linked`. */
    data object AlreadyLinked : OidcFailure

    /** 409 `last_login_method`: unlinking would leave no way to sign in. */
    data object LastLoginMethod : OidcFailure

    /** 403 `reauth_required`: the step-up proof is missing, used or too old. */
    data object ReauthRequired : OidcFailure

    /** 401 `invalid_credentials`: the password proof was wrong. */
    data object InvalidPassword : OidcFailure

    /** 401 `session_expired`: the current sign-in is no longer valid. */
    data object SessionExpired : OidcFailure

    /** 404 `not_found`: the identity or user is not visible to the caller. */
    data object NotFound : OidcFailure

    /** 422 `validation_error`. */
    data object Validation : OidcFailure

    /** The provider reported an error to the app (`error=oidc_failed`), e.g. sign-in cancelled. */
    data object ProviderError : OidcFailure

    /** The re-sign-in belongs to a different account than the one on this device. */
    data object AccountMismatch : OidcFailure

    data object Offline : OidcFailure

    data class Other(val status: Int?) : OidcFailure
}

/** A non-2xx answer from the unauthenticated (OkHttp) login client. */
class OidcHttpException(val status: Int, val code: String?, val retryAfterSeconds: Long?) :
    Exception("HTTP $status")

fun oidcFailureFor(status: Int, code: String?, retryAfterSeconds: Long? = null): OidcFailure =
    when {
        status == 429 || code == "rate_limited" -> OidcFailure.RateLimited(retryAfterSeconds)
        code == "oidc_identity_unlinked" -> OidcFailure.Unlinked
        code == "oidc_unavailable" || status == 503 -> OidcFailure.Unavailable
        code == "oidc_disabled" -> OidcFailure.Unavailable
        code == "identity_already_linked" -> OidcFailure.AlreadyLinked
        code == "last_login_method" -> OidcFailure.LastLoginMethod
        code == "reauth_required" -> OidcFailure.ReauthRequired
        code == "invalid_oidc_flow" -> OidcFailure.Expired
        code == "invalid_credentials" -> OidcFailure.InvalidPassword
        code == "session_expired" -> OidcFailure.SessionExpired
        code == "validation_error" || status == 422 -> OidcFailure.Validation
        code == "not_found" || status == 404 -> OidcFailure.NotFound
        else -> OidcFailure.Other(status)
    }

/**
 * `Retry-After` as whole seconds. The HTTP-date form is not interpreted (the server sends seconds);
 * anything unparseable is null.
 */
fun parseRetryAfter(value: String?): Long? = value?.trim()?.toLongOrNull()?.takeIf { it >= 0 }

private val ERROR_JSON = Json { ignoreUnknownKeys = true }

/** Turns any thrown request error into an [OidcFailure]. Pure, so it is unit-tested. */
fun Throwable.toOidcFailure(): OidcFailure {
    var current: Throwable? = this
    while (current != null) {
        when (current) {
            is OidcFailureException -> return current.failure
            is OidcHttpException ->
                return oidcFailureFor(current.status, current.code, current.retryAfterSeconds)
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
                val retryAfter = parseRetryAfter(current.response()?.headers()?.get("Retry-After"))
                return oidcFailureFor(current.code(), code, retryAfter)
            }
            is IOException -> return OidcFailure.Offline
        }
        current = current.cause
    }
    return OidcFailure.Other(null)
}

/** Carries an already classified [OidcFailure] through code that only knows exceptions. */
class OidcFailureException(val failure: OidcFailure) : Exception(failure.toString())

/** The `error.code` of a v2 error body, or null. */
fun errorCodeOf(body: String): String? =
    runCatching { ERROR_JSON.decodeFromString<ApiErrorEnvelopeDto>(body).error?.code }.getOrNull()

/** The [OidcFailure] of a failed [Result] (see [OidcCoordinator]'s calls), null on success. */
fun Result<*>.oidcFailure(): OidcFailure? = exceptionOrNull()?.toOidcFailure()
