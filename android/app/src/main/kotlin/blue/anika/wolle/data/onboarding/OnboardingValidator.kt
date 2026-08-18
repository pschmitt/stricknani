package blue.anika.wolle.data.onboarding

import blue.anika.wolle.di.ValidationClient
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** Why a connection attempt during onboarding didn't succeed, for a precise inline error. */
sealed interface OnboardingError {
    /** The entered server URL isn't a valid absolute HTTP(S) URL. */
    data object MalformedUrl : OnboardingError

    /** The server was reachable, but `/api/v1/meta` didn't respond like a Stricknani server. */
    data class NotAStricknaniServer(val code: Int) : OnboardingError

    /** The server was reachable and is Stricknani, but rejected the token (HTTP 401/403). */
    data object Unauthorized : OnboardingError

    /** The server was unreachable (DNS/connect/timeout failure). */
    data object Unreachable : OnboardingError

    /** The server responded, but not with success and not with an auth-shaped error. */
    data class ServerError(val code: Int) : OnboardingError
}

class OnboardingValidationException(val error: OnboardingError, cause: Throwable? = null) :
    Exception(cause)

/**
 * Confirms a server URL + API token pair actually authenticates against a reachable Stricknani
 * server *before* [blue.anika.wolle.data.settings.SettingsRepository] persists them. Deliberately
 * uses its own unauthenticated, static-baseurl OkHttpClient ([ValidationClient]) rather than the
 * app's normal OkHttp stack, since that stack's interceptors read the currently *saved*
 * connection - which, during onboarding, is exactly what's being validated before being saved.
 * Same two-step pattern as syncwich's `OnboardingValidator`, adapted for Stricknani's endpoints:
 * `/api/v1/meta` needs no auth (it exists so the app can identify "is this even a Stricknani
 * server" before a token is entered), so a second, authenticated request against a real
 * `require_api_token` endpoint is needed to actually validate the token.
 */
@Singleton
class OnboardingValidator @Inject constructor(@ValidationClient private val client: OkHttpClient) {

    suspend fun validate(serverUrl: String, apiToken: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val baseUrl =
                serverUrl.trim().trimEnd('/').toHttpUrlOrNull()
                    ?: return@withContext Result.failure(
                        OnboardingValidationException(OnboardingError.MalformedUrl)
                    )

            val serverCheck = checkIsStricknaniServer(baseUrl)
            if (serverCheck.isFailure) {
                return@withContext Result.failure(serverCheck.exceptionOrNull()!!)
            }
            checkTokenAuthorizes(baseUrl, apiToken)
        }

    private fun checkIsStricknaniServer(baseUrl: HttpUrl): Result<Unit> {
        val request =
            Request.Builder().url(baseUrl.newBuilder().addPathSegments("api/v1/meta").build()).build()
        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(Unit)
                } else {
                    Result.failure(
                        OnboardingValidationException(
                            OnboardingError.NotAStricknaniServer(response.code)
                        )
                    )
                }
            }
        } catch (e: IOException) {
            Result.failure(OnboardingValidationException(OnboardingError.Unreachable, e))
        }
    }

    private fun checkTokenAuthorizes(baseUrl: HttpUrl, apiToken: String): Result<Unit> {
        val request =
            Request.Builder()
                .url(baseUrl.newBuilder().addPathSegments("api/v1/categories").build())
                .header("Authorization", "Bearer ${apiToken.trim()}")
                .build()
        return try {
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> Result.success(Unit)
                    response.code == 401 || response.code == 403 ->
                        Result.failure(OnboardingValidationException(OnboardingError.Unauthorized))
                    else ->
                        Result.failure(
                            OnboardingValidationException(OnboardingError.ServerError(response.code))
                        )
                }
            }
        } catch (e: IOException) {
            Result.failure(OnboardingValidationException(OnboardingError.Unreachable, e))
        }
    }
}
