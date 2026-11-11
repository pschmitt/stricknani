package blue.anika.wolle.data.api.v2

import blue.anika.wolle.data.api.dto.TrashListDto
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * `src/api_v2/trash.rs`: deleted items wait 90 days in the trash. Online-only. [segment] is the
 * plural of the item kind (see `TrashKind.segment`).
 */
interface TrashV2Api {
    @GET("api/v2/trash") suspend fun list(@Query("scope") scope: String): TrashListDto

    /** 204; 404 when the item is not in the trash (or not yours); 409 `parent_in_trash`. */
    @POST("api/v2/trash/{segment}/{id}/restore")
    suspend fun restore(@Path("segment") segment: String, @Path("id") id: Long)

    /** Delete forever: 204. */
    @DELETE("api/v2/trash/{segment}/{id}")
    suspend fun purge(@Path("segment") segment: String, @Path("id") id: Long)
}
