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

@Serializable private data class TokenMintResponseV2(val data: TokenMintResponse)

/**
 * The password-login onboarding path (SNA-13, matches syncwich's `PasswordTokenMinter`): trades an
 * email/password for a freshly minted PAT via `/api/v2/auth/token` (`/api/v1/auth/token` on a
 * server without v2, SNA-87), so the user never has to manually visit the web Settings page first.
 * Unlike syncwich's Mealie-backed two-step password-then-mint dance, Stricknani's own backend does
 * both in one request since it controls both ends.
 *
 * Uses [ValidationClient] (a plain, unauthenticated OkHttpClient against a caller-supplied base
 * URL) rather than the app's normal Retrofit stack, for the same reason [OnboardingValidator] does:
 * that stack's interceptors read the currently *saved* connection, which during onboarding is
 * exactly what's being set up. The password itself is only ever a local `val` for the duration of
 * this one request; it's never persisted or logged (this app has no HTTP logging interceptor on
 * [ValidationClient] to begin with).
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

            val v2 =
                try {
                    when (val probe = probeV2(client, json, baseUrl)) {
                        V2Probe.Supported -> true
                        V2Probe.Unsupported -> false
                        is V2Probe.Failed ->
                            return@withContext Result.failure(
                                OnboardingValidationException(
                                    OnboardingError.ServerError(probe.code)
                                )
                            )
                    }
                } catch (e: IOException) {
                    return@withContext Result.failure(
                        OnboardingValidationException(OnboardingError.Unreachable, e)
                    )
                }

            val body =
                json
                    .encodeToString(TokenMintRequest(email.trim(), password, tokenName))
                    .toRequestBody("application/json".toMediaType())
            val path = if (v2) "api/v2/auth/token" else "api/v1/auth/token"
            val request =
                Request.Builder()
                    .url(baseUrl.newBuilder().addPathSegments(path).build())
                    .post(body)
                    .build()

            try {
                client.newCall(request).execute().use { response ->
                    val code = response.code
                    when {
                        response.isSuccessful -> {
                            val text = response.body.string()
                            val token =
                                if (v2) json.decodeFromString<TokenMintResponseV2>(text).data.token
                                else json.decodeFromString<TokenMintResponse>(text).token
                            Result.success(token)
                        }
                        // v2: 401 invalid_credentials, 422 validation_error (unusable email or
                        // password); v1: 401/403.
                        code == 401 || code == 403 || (v2 && code == 422) ->
                            Result.failure(
                                OnboardingValidationException(OnboardingError.InvalidCredentials)
                            )
                        code == 429 ->
                            Result.failure(
                                OnboardingValidationException(OnboardingError.RateLimited)
                            )
                        else ->
                            Result.failure(
                                OnboardingValidationException(OnboardingError.ServerError(code))
                            )
                    }
                }
            } catch (e: IOException) {
                Result.failure(OnboardingValidationException(OnboardingError.Unreachable, e))
            }
        }
}
