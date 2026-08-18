package blue.anika.wolle.data.onboarding

import blue.anika.wolle.di.ValidationClient
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
private data class TokenMintRequest(
    val email: String,
    val password: String,
    @SerialName("token_name") val tokenName: String,
)

@Serializable private data class TokenMintResponse(val token: String)

/**
 * The password-login onboarding path (SNA-13, matches syncwich's `PasswordTokenMinter`): trades an
 * email/password for a freshly minted PAT via `/api/v1/auth/token`, so the user never has to
 * manually visit the web Settings page first. Unlike syncwich's Mealie-backed two-step
 * password-then-mint dance, Stricknani's own backend does both in one request since it controls
 * both ends.
 *
 * Uses [ValidationClient] (a plain, unauthenticated OkHttpClient against a caller-supplied base
 * URL) rather than the app's normal Retrofit stack, for the same reason [OnboardingValidator]
 * does: that stack's interceptors read the currently *saved* connection, which during onboarding
 * is exactly what's being set up. The password itself is only ever a local `val` for the duration
 * of this one request; it's never persisted or logged (this app has no HTTP logging interceptor
 * on [ValidationClient] to begin with).
 */
@Singleton
class PasswordTokenMinter
@Inject
constructor(@ValidationClient private val client: OkHttpClient, private val json: Json) {

    suspend fun mintToken(
        serverUrl: String,
        email: String,
        password: String,
        tokenName: String,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            val baseUrl =
                serverUrl.trim().trimEnd('/').toHttpUrlOrNull()
                    ?: return@withContext Result.failure(
                        OnboardingValidationException(OnboardingError.MalformedUrl)
                    )

            val body =
                json
                    .encodeToString(TokenMintRequest(email.trim(), password, tokenName))
                    .toRequestBody("application/json".toMediaType())
            val request =
                Request.Builder()
                    .url(baseUrl.newBuilder().addPathSegments("api/v1/auth/token").build())
                    .post(body)
                    .build()

            try {
                client.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> {
                            val decoded =
                                json.decodeFromString<TokenMintResponse>(response.body.string())
                            Result.success(decoded.token)
                        }
                        response.code == 401 || response.code == 403 ->
                            Result.failure(
                                OnboardingValidationException(OnboardingError.InvalidCredentials)
                            )
                        else ->
                            Result.failure(
                                OnboardingValidationException(
                                    OnboardingError.ServerError(response.code)
                                )
                            )
                    }
                }
            } catch (e: IOException) {
                Result.failure(OnboardingValidationException(OnboardingError.Unreachable, e))
            }
        }
}
