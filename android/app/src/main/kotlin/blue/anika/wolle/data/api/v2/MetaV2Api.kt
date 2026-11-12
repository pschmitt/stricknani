package blue.anika.wolle.data.api.v2

import blue.anika.wolle.data.errorreporting.ErrorReportingDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET

/**
 * `src/api_v2/meta.rs`: `GET /api/v2/meta` answers 200 for anyone. With a valid Bearer token
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
    /** Commit the server was built from (`9a7bfc70`, maybe `-dirty`); absent on older servers. */
    val revision: String? = null,
    @SerialName("is_admin") val isAdmin: Boolean = false,
    val user: MetaV2UserDto? = null,
    /** Id of the calling PAT; only sent to bearer callers by servers with bearer account access. */
    @SerialName("current_token_id") val currentTokenId: Long? = null,
    /** Optional, operator-configured error reporting; absent on servers that predate it. */
    @SerialName("error_reporting") val errorReporting: ErrorReportingDto? = null,
    /** What link imports can do on this server; absent on servers without the feature. */
    val imports: MetaV2ImportsDto? = null,
    /** Saving source links to the web archive is enabled (the archive control). */
    val archive: Boolean = false,
    /**
     * Changes whenever an administrator restored a backup (`data_epoch`); a client that remembers
     * another value discards its cache and queued writes. Absent on servers without backups.
     */
    @SerialName("data_epoch") val dataEpoch: String? = null,
    /** Outcome of the last backup restore, so a client that waited for the restart can tell. */
    @SerialName("last_restore") val lastRestore: MetaV2LastRestoreDto? = null,
)

/** `meta.last_restore`: `succeeded` or `failed`, and when (RFC 3339). */
@Serializable
data class MetaV2LastRestoreDto(
    val status: String = "",
    @SerialName("finished_at") val finishedAt: String = "",
)

/** `meta.imports`: link import and AI availability. */
@Serializable data class MetaV2ImportsDto(val url: Boolean = false, val ai: Boolean = false)

@Serializable
data class MetaV2UserDto(
    val id: Long,
    val email: String,
    @SerialName("is_admin") val isAdmin: Boolean = false,
    /** A `/media/thumbnails/users/...` path; absent on servers that do not report it yet. */
    @SerialName("avatar_url") val avatarUrl: String? = null,
)
