package blue.anika.wolle.data.api

import blue.anika.wolle.data.oidc.errorCodeOf
import blue.anika.wolle.data.settings.SettingsRepository
import javax.inject.Inject
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Watches the app's own requests for a rejected token and tells [SettingsRepository], so the UI can
 * offer "sign in again" (OIDC tokens expire; any token can be revoked or lost with the server's
 * database). It only observes: the response goes on unchanged, so callers keep their normal failure
 * handling, and nothing is wiped or retried here.
 *
 * A rejected token is `401 session_expired` from a v2 server, or any 401 on a v1 data route
 * (`/api/v1/...`, which answers with its own body). `401 invalid_credentials` (a wrong password
 * proof) is deliberately not an expiry. A successful answer to the same token lifts the flag again.
 */
class SessionExpiryInterceptor
internal constructor(
    private val onExpired: (String?) -> Unit,
    private val onAccepted: (String?) -> Unit = {},
) : Interceptor {
    @Inject
    constructor(
        settings: SettingsRepository
    ) : this({ settings.markSessionExpired(it) }, { settings.markSessionValid(it) })

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val token = request.header("Authorization")?.removePrefix("Bearer ")
        if (response.code == 401) {
            val body = runCatching { response.peekBody(PEEK_BYTES).string() }.getOrDefault("")
            if (isRejectedTokenResponse(request.url.encodedPath, body)) onExpired(token)
        } else if (response.isSuccessful) {
            onAccepted(token)
        }
        return response
    }

    private companion object {
        const val PEEK_BYTES = 8 * 1024L
    }
}

/** True for a v2 error body whose `error.code` is `session_expired`. */
internal fun isSessionExpiredBody(body: String): Boolean = errorCodeOf(body) == "session_expired"

/** A 401 that says "this token is no longer good" (see [SessionExpiryInterceptor]). */
internal fun isRejectedTokenResponse(path: String, body: String): Boolean =
    when (errorCodeOf(body)) {
        "session_expired",
        // An impersonation credential that expired, was stopped or whose accounts changed.
        "impersonation_expired" -> true
        null -> path.startsWith("/api/v1/")
        else -> false
    }
