package blue.anika.wolle.data.onboarding

import blue.anika.wolle.data.api.v2.V2Envelope
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable internal data class MetaProbeUser(val id: Long = 0)

@Serializable internal data class MetaProbeBody(val user: MetaProbeUser? = null)

/** What an anonymous `GET /api/v2/meta` says about a server. */
sealed interface V2Probe {
    /** 200 `{data:{...}}`: a Stricknani server with API v2. */
    data object Supported : V2Probe

    /** 404/405, or a 200 that is not a v2 meta body: an older server, use v1. */
    data object Unsupported : V2Probe

    /** Any other status (401, 5xx...): never a reason to fall back to v1. */
    data class Failed(val code: Int) : V2Probe
}

/**
 * Onboarding's server identification (SNA-87): `GET /api/v2/meta` answers 200 for anonymous callers
 * with `user: null` on a Stricknani server that has v2. Only a server that does not have the route
 * (404/405) selects v1; 401/403/5xx are reported as failures, and offline throws [IOException].
 */
internal fun probeV2(client: OkHttpClient, json: Json, baseUrl: HttpUrl): V2Probe {
    val request =
        Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments("api/v2/meta").build())
            .header("Accept", "application/json")
            .build()
    client.newCall(request).execute().use { response ->
        return when {
            response.isSuccessful ->
                if (
                    runCatching {
                            json.decodeFromString<V2Envelope<MetaProbeBody>>(response.body.string())
                        }
                        .isSuccess
                )
                    V2Probe.Supported
                else V2Probe.Unsupported
            response.code == 404 || response.code == 405 -> V2Probe.Unsupported
            else -> V2Probe.Failed(response.code)
        }
    }
}
