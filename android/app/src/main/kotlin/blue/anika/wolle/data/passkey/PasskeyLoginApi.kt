package blue.anika.wolle.data.passkey

import blue.anika.wolle.data.api.v2.V2Envelope
import blue.anika.wolle.data.oidc.errorCodeOf
import blue.anika.wolle.di.ValidationClient
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The unauthenticated passkey calls: they run before a token exists, against an explicit server URL
 * rather than the saved connection (same reasons as `OidcLoginApi`). No cookies, no CSRF: the
 * result is an API-purpose PAT, like the password exchange.
 */
interface PasskeyLoginApi {
    /** `data.auth.passkeys` of an anonymous `GET /api/v2/meta`; false when unknown or older. */
    suspend fun available(serverUrl: String): Boolean

    /**
     * A sign-in challenge. Throws [PasskeyHttpException] on a non-2xx answer, [IOException]
     * offline.
     */
    suspend fun options(serverUrl: String): ChallengeDto

    /** The PAT for a verified assertion. */
    suspend fun exchange(
        serverUrl: String,
        challengeId: String,
        credential: JsonElement,
        deviceName: String,
    ): String
}

@Singleton
class HttpPasskeyLoginApi
@Inject
constructor(@ValidationClient private val client: OkHttpClient, private val json: Json) :
    PasskeyLoginApi {

    override suspend fun available(serverUrl: String): Boolean =
        withContext(Dispatchers.IO) {
            val base = baseUrl(serverUrl) ?: return@withContext false
            val request =
                Request.Builder()
                    .url(base.newBuilder().addPathSegments("api/v2/meta").build())
                    .header("Accept", "application/json")
                    .build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use false
                    runCatching {
                            json
                                .decodeFromString<V2Envelope<MetaPasskeyDto>>(
                                    response.body.string()
                                )
                                .data
                                .auth
                                ?.passkeys == true
                        }
                        .getOrDefault(false)
                }
            } catch (_: IOException) {
                false
            }
        }

    override suspend fun options(serverUrl: String): ChallengeDto =
        post<ChallengeDto>(serverUrl, "api/v2/auth/passkey/token/options", "{}")

    override suspend fun exchange(
        serverUrl: String,
        challengeId: String,
        credential: JsonElement,
        deviceName: String,
    ): String =
        post<PasskeyTokenResponse>(
                serverUrl,
                "api/v2/auth/passkey/token",
                json.encodeToString(PasskeyTokenRequest(challengeId, credential, deviceName)),
            )
            .token

    private suspend inline fun <reified T> post(serverUrl: String, path: String, body: String): T =
        withContext(Dispatchers.IO) {
            val base = baseUrl(serverUrl) ?: throw PasskeyException(PasskeyFailure.Other(null))
            val request =
                Request.Builder()
                    .url(base.newBuilder().addPathSegments(path).build())
                    .header("Accept", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
            client.newCall(request).execute().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) {
                    throw PasskeyHttpException(
                        status = response.code,
                        code = errorCodeOf(text),
                        retryAfterSeconds = response.header("Retry-After")?.trim()?.toLongOrNull(),
                    )
                }
                json.decodeFromString<V2Envelope<T>>(text).data
            }
        }

    private fun baseUrl(serverUrl: String): HttpUrl? =
        serverUrl.trim().trimEnd('/').toHttpUrlOrNull()
}
