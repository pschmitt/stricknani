package blue.anika.wolle.data.api.v2

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET

/**
 * `rust/src/api_v2/meta.rs`: `GET /api/v2/meta` answers 200 for anyone. With a valid Bearer token
 * `data.user` is the token's owner; without one (or with a cookie-less request) `user` is null. An
 * invalid Bearer token is a 401.
 */
interface MetaV2Api {
    @GET("api/v2/meta") suspend fun getMeta(): V2Envelope<MetaV2Dto>
}

@Serializable
data class MetaV2Dto(
    val version: String? = null,
    @SerialName("build_id") val buildId: String? = null,
    @SerialName("is_admin") val isAdmin: Boolean = false,
    val user: MetaV2UserDto? = null,
    /** Id of the calling PAT; only sent to bearer callers by servers with bearer account access. */
    @SerialName("current_token_id") val currentTokenId: Long? = null,
)

@Serializable
data class MetaV2UserDto(
    val id: Long,
    val email: String,
    @SerialName("is_admin") val isAdmin: Boolean = false,
    /** A `/media/thumbnails/users/...` path; absent on servers that do not report it yet. */
    @SerialName("avatar_url") val avatarUrl: String? = null,
)
