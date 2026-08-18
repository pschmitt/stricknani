package blue.anika.wolle.data.api

import blue.anika.wolle.data.api.dto.YarnDto
import blue.anika.wolle.data.api.dto.YarnPageDto
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * `stricknani/routes/api/yarns.py`. Read (list/detail) + favorite toggle - see `ProjectsApi`'s
 * kdoc for why favorite/unfavorite don't wait on the offline write queue (SNA-8). Create/update
 * /delete/photos are SNA-8/SNA-10.
 */
interface YarnsApi {
    @GET("api/v1/yarns")
    suspend fun listYarns(
        @Query("page") page: Int = 1,
        @Query("favorite") favorite: Boolean? = null,
    ): YarnPageDto

    @GET("api/v1/yarns/{yarnId}") suspend fun getYarn(@Path("yarnId") yarnId: Int): YarnDto

    @POST("api/v1/yarns/{yarnId}/favorite")
    suspend fun favoriteYarn(@Path("yarnId") yarnId: Int): YarnDto

    @DELETE("api/v1/yarns/{yarnId}/favorite")
    suspend fun unfavoriteYarn(@Path("yarnId") yarnId: Int): YarnDto
}
