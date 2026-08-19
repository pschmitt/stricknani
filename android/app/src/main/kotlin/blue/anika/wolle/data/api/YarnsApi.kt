package blue.anika.wolle.data.api

import blue.anika.wolle.data.api.dto.YarnDto
import blue.anika.wolle.data.api.dto.YarnPageDto
import blue.anika.wolle.data.api.dto.YarnPhotoDto
import blue.anika.wolle.data.api.dto.YarnWriteRequest
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import okhttp3.MultipartBody

/**
 * `stricknani/routes/api/yarns.py`. Read (list/detail), favorite toggle (see `ProjectsApi`'s kdoc
 * for why it doesn't wait on the offline write queue), and create/update/delete (queued through the
 * write queue - SNA-8). Photo uploads use the same queued multipart replay path as project images.
 */
interface YarnsApi {
    @GET("api/v1/yarns")
    suspend fun listYarns(
        @Query("page") page: Int = 1,
        @Query("favorite") favorite: Boolean? = null,
    ): YarnPageDto

    @GET("api/v1/yarns/{yarnId}") suspend fun getYarn(@Path("yarnId") yarnId: Int): YarnDto

    @POST("api/v1/yarns") suspend fun createYarn(@Body request: YarnWriteRequest): YarnDto

    @PUT("api/v1/yarns/{yarnId}")
    suspend fun updateYarn(@Path("yarnId") yarnId: Int, @Body request: YarnWriteRequest): YarnDto

    @DELETE("api/v1/yarns/{yarnId}") suspend fun deleteYarn(@Path("yarnId") yarnId: Int)

    @POST("api/v1/yarns/{yarnId}/favorite")
    suspend fun favoriteYarn(@Path("yarnId") yarnId: Int): YarnDto

    @DELETE("api/v1/yarns/{yarnId}/favorite")
    suspend fun unfavoriteYarn(@Path("yarnId") yarnId: Int): YarnDto

    @Multipart
    @POST("api/v1/yarns/{yarnId}/photos")
    suspend fun uploadPhoto(
        @Path("yarnId") yarnId: Int,
        @Part file: MultipartBody.Part,
    ): YarnPhotoDto
}
