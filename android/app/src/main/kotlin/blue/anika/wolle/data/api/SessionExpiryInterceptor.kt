package blue.anika.wolle.data.api

import blue.anika.wolle.data.oidc.errorCodeOf
import blue.anika.wolle.data.settings.SettingsRepository
import javax.inject.Inject
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Watches for `401 session_expired` on the app's own requests and tells [SettingsRepository] so the
 * UI can offer "sign in again" (OIDC tokens expire). It only observes: the response goes on
 * unchanged, so callers keep their normal failure handling, and nothing is wiped or retried here.
 * `401 invalid_credentials` (a wrong password proof) is deliberately not an expiry.
 */
class SessionExpiryInterceptor internal constructor(private val onExpired: (String?) -> Unit) :
    Interceptor {
    @Inject constructor(settings: SettingsRepository) : this({ settings.markSessionExpired(it) })

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (response.code == 401) {
            val body = runCatching { response.peekBody(PEEK_BYTES).string() }.getOrDefault("")
            if (isSessionExpiredBody(body)) {
                onExpired(request.header("Authorization")?.removePrefix("Bearer "))
            }
        }
        return response
    }

    private companion object {
        const val PEEK_BYTES = 8 * 1024L
    }
}

/** True for a v2 error body whose `error.code` is `session_expired`. */
internal fun isSessionExpiredBody(body: String): Boolean = errorCodeOf(body) == "session_expired"
