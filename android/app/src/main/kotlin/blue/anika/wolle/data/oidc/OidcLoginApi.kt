package blue.anika.wolle.data.oidc

import blue.anika.wolle.data.api.v2.V2Envelope
import blue.anika.wolle.di.ValidationClient
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The unauthenticated OIDC calls: they run before a token exists (or while the stored one is
 * expired, where sending it would only get a 401), against an explicit server URL rather than the
 * saved connection.
 */
interface OidcLoginApi {
    /**
     * `data.auth.oidc` of an anonymous `GET /api/v2/meta`. Empty when the server lists none, is
     * older, is unreachable, or answers oddly: OIDC UI stays hidden then, it is never an error.
     */
    suspend fun providers(serverUrl: String): List<OidcProvider>

    /** Throws [OidcHttpException] on a non-2xx answer and [IOException] when offline. */
    suspend fun start(serverUrl: String, request: OidcStartRequest): OidcStartResponse

    suspend fun exchange(
        serverUrl: String,
        request: OidcLoginExchangeRequest,
    ): OidcLoginExchangeResponse

    /** Email of the token's owner from `GET /api/v2/meta`, or null when it cannot be told. */
    suspend fun currentUserEmail(serverUrl: String, token: String): String?
}

@Serializable private data class MetaAuthDto(val oidc: List<OidcProvider> = emptyList())

@Serializable
private data class MetaOidcDto(val auth: MetaAuthDto? = null, val user: MetaUserDto? = null)

@Serializable private data class MetaUserDto(val email: String? = null)

@Singleton
class HttpOidcLoginApi
@Inject
constructor(@ValidationClient private val client: OkHttpClient, private val json: Json) :
    OidcLoginApi {

    override suspend fun providers(serverUrl: String): List<OidcProvider> =
        withContext(Dispatchers.IO) {
            val base = baseUrl(serverUrl) ?: return@withContext emptyList()
            val request =
                Request.Builder()
                    .url(base.newBuilder().addPathSegments("api/v2/meta").build())
                    .header("Accept", "application/json")
                    .build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use emptyList()
                    runCatching {
                            json
                                .decodeFromString<V2Envelope<MetaOidcDto>>(response.body.string())
                                .data
                                .auth
                                ?.oidc
                                .orEmpty()
                                .filter { it.id.isNotBlank() }
                        }
                        .getOrDefault(emptyList())
                }
            } catch (_: IOException) {
                emptyList()
            }
        }

    override suspend fun start(serverUrl: String, request: OidcStartRequest): OidcStartResponse =
        post(serverUrl, "api/v2/auth/oidc/start", json.encodeToString(request))

    override suspend fun exchange(
        serverUrl: String,
        request: OidcLoginExchangeRequest,
    ): OidcLoginExchangeResponse =
        post(serverUrl, "api/v2/auth/oidc/exchange", json.encodeToString(request))

    override suspend fun currentUserEmail(serverUrl: String, token: String): String? =
        withContext(Dispatchers.IO) {
            val base = baseUrl(serverUrl) ?: return@withContext null
            val request =
                Request.Builder()
                    .url(base.newBuilder().addPathSegments("api/v2/meta").build())
                    .header("Authorization", "Bearer $token")
                    .build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    runCatching {
                            json
                                .decodeFromString<V2Envelope<MetaOidcDto>>(response.body.string())
                                .data
                                .user
                                ?.email
                        }
                        .getOrNull()
                }
            } catch (_: IOException) {
                null
            }
        }

    private suspend inline fun <reified T> post(serverUrl: String, path: String, body: String): T =
        withContext(Dispatchers.IO) {
            val base = baseUrl(serverUrl) ?: throw OidcFailureException(OidcFailure.Other(null))
            val request =
                Request.Builder()
                    .url(base.newBuilder().addPathSegments(path).build())
                    .header("Accept", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
            client.newCall(request).execute().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) {
                    throw OidcHttpException(
                        status = response.code,
                        code = errorCodeOf(text),
                        retryAfterSeconds = parseRetryAfter(response.header("Retry-After")),
                    )
                }
                json.decodeFromString<V2Envelope<T>>(text).data
            }
        }

    private fun baseUrl(serverUrl: String): HttpUrl? =
        serverUrl.trim().trimEnd('/').toHttpUrlOrNull()
}
