package blue.anika.wolle.data.api.v2

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * `src/api_v2/archive.rs`: save a project's or yarn's source link to the web archive and read the
 * owner's current archive job. [entity] is `projects` or `yarns`. Offered only when `GET
 * /api/v2/meta` reports `archive`.
 */
interface ArchiveV2Api {
    @GET("api/v2/{entity}/{id}/archive")
    suspend fun status(
        @Path("entity") entity: String,
        @Path("id") id: Int,
    ): V2Envelope<ArchiveStatusDto>

    @POST("api/v2/{entity}/{id}/archive")
    suspend fun request(
        @Path("entity") entity: String,
        @Path("id") id: Int,
        @Body body: ArchiveRequestBody = ArchiveRequestBody(),
    ): V2Envelope<ArchiveStatusDto>
}

@Serializable class ArchiveRequestBody

/** [state]: `pending`, `running`, `archived`, `failed`, `superseded`, or null (never archived). */
@Serializable
data class ArchiveStatusDto(
    val state: String? = null,
    @SerialName("updated_at") val updatedAt: Long? = null,
)
