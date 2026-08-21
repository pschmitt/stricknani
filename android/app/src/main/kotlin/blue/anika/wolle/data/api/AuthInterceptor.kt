package blue.anika.wolle.data.api

import blue.anika.wolle.data.settings.SettingsRepository
import javax.inject.Inject
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Attaches the stored personal access token - the API only ever accepts `Bearer <token>` (see
 * `require_api_token` in `stricknani/routes/auth.py`), no legacy scheme to branch on.
 */
class AuthInterceptor @Inject constructor(private val settingsRepository: SettingsRepository) :
    Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val token = settingsRepository.credentials.value.apiToken
        val request = chain.request()
        val authorized =
            if (token.isBlank()) request
            else request.newBuilder().header("Authorization", "Bearer $token").build()
        return chain.proceed(authorized)
    }
}
