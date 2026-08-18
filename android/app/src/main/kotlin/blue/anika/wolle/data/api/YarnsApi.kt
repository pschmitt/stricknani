package blue.anika.wolle.data.api

import blue.anika.wolle.data.api.dto.YarnDto
import blue.anika.wolle.data.api.dto.YarnPageDto
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * `stricknani/routes/api/yarns.py`. Only the read (list/detail) surface used by SNA-7's sync
 * engine - write/upload endpoints (create/update/delete/favorite/photos) are SNA-8/SNA-10.
 */
interface YarnsApi {
    @GET("api/v1/yarns")
    suspend fun listYarns(
        @Query("page") page: Int = 1,
        @Query("favorite") favorite: Boolean? = null,
    ): YarnPageDto

    @GET("api/v1/yarns/{yarnId}") suspend fun getYarn(@Path("yarnId") yarnId: Int): YarnDto
}
