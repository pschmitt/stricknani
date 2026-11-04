package blue.anika.wolle.data.onboarding

import blue.anika.wolle.data.api.v2.V2Envelope
import blue.anika.wolle.di.ValidationClient
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
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
private data class RegisterRequest(
    val email: String,
    val password: String,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("device_name")
    val deviceName: String? = null,
)

@Serializable private data class RegisterResponse(val token: String)

@Serializable
private data class RegistrationMeta(
    @SerialName("registration_enabled") val enabled: Boolean? = null
)

/** Why creating an account did not work, for a precise inline message. */
sealed interface RegistrationError {
    /** The server has registration turned off (HTTP 404 `signup_disabled`). */
    data object Closed : RegistrationError

    /** The email or password was refused (HTTP 422). */
    data object Invalid : RegistrationError

    /**
     * The account could not be created (HTTP 409). The server words this generically on purpose and
     * so does the app: it must not say whether the email is already taken.
     */
    data object NotCreated : RegistrationError

    data object RateLimited : RegistrationError

    data object Unreachable : RegistrationError

    data class ServerError(val code: Int) : RegistrationError
}

class RegistrationException(val error: RegistrationError, cause: Throwable? = null) :
    Exception(error.toString(), cause)

/**
 * Self-service registration against a server the user typed in, before any connection is saved
 * (hence [ValidationClient], like [PasswordTokenMinter]). `GET /api/v2/meta` advertises
 * `registration_enabled`; `POST /api/v2/auth/register` creates a member and returns an API token,
 * so a new account is signed in without a second round-trip.
 */
@Singleton
class RegistrationClient
@Inject
constructor(@ValidationClient private val client: OkHttpClient, private val json: Json) {

    /**
     * Whether the server at [serverUrl] allows registration: true or false as it says, or null when
     * it cannot be told (bad URL, offline, an older server without the flag). The UI shows neither
     * the form nor the "set up your own" card for null.
     */
    suspend fun isOpen(serverUrl: String): Boolean? =
        withContext(Dispatchers.IO) {
            val base = serverUrl.trim().trimEnd('/').toHttpUrlOrNull() ?: return@withContext null
            val request =
                Request.Builder()
                    .url(base.newBuilder().addPathSegments("api/v2/meta").build())
                    .header("Accept", "application/json")
                    .build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    runCatching {
                            json
                                .decodeFromString<V2Envelope<RegistrationMeta>>(
                                    response.body.string()
                                )
                                .data
                                .enabled
                        }
                        .getOrNull()
                }
            } catch (_: IOException) {
                null
            }
        }

    /** Creates a member account and returns its API token. */
    suspend fun register(
        serverUrl: String,
        email: String,
        password: String,
        deviceName: String? = null,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            val base =
                serverUrl.trim().trimEnd('/').toHttpUrlOrNull()
                    ?: return@withContext Result.failure(
                        OnboardingValidationException(OnboardingError.MalformedUrl)
                    )
            val body =
                json
                    .encodeToString(RegisterRequest(email.trim(), password, deviceName))
                    .toRequestBody("application/json".toMediaType())
            val request =
                Request.Builder()
                    .url(base.newBuilder().addPathSegments("api/v2/auth/register").build())
                    .header("Accept", "application/json")
                    .post(body)
                    .build()
            try {
                client.newCall(request).execute().use { response ->
                    when (val code = response.code) {
                        in 200..299 ->
                            Result.success(
                                json
                                    .decodeFromString<V2Envelope<RegisterResponse>>(
                                        response.body.string()
                                    )
                                    .data
                                    .token
                            )
                        404 -> failure(RegistrationError.Closed)
                        409 -> failure(RegistrationError.NotCreated)
                        400,
                        422 -> failure(RegistrationError.Invalid)
                        429 -> failure(RegistrationError.RateLimited)
                        else -> failure(RegistrationError.ServerError(code))
                    }
                }
            } catch (e: IOException) {
                Result.failure(RegistrationException(RegistrationError.Unreachable, e))
            }
        }

    private fun failure(error: RegistrationError): Result<String> =
        Result.failure(RegistrationException(error))
}
