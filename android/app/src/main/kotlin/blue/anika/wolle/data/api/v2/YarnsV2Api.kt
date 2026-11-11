package blue.anika.wolle.data.api.v2

import blue.anika.wolle.data.api.dto.YarnListItemDto
import blue.anika.wolle.data.api.dto.YarnPhotoDto
import blue.anika.wolle.data.api.dto.YarnWriteRequest
import okhttp3.MultipartBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/** `src/api_v2/{yarns,media}.rs`. Not wired in yet - see [ProjectsV2Api]. */
interface YarnsV2Api {
    @GET("api/v2/yarns")
    suspend fun listYarns(
        @Query("page") page: Int = 1,
        @Query("favorite") favorite: Boolean? = null,
        @Query("q") query: String? = null,
        /** `me`, `shared` or a user id; same values as the website's `?owner=`. */
        @Query("owner") owner: String? = null,
        /** `only` lists the trash instead of the live yarns. */
        @Query("trashed") trashed: String? = null,
        /** `mine` or `all` (administrators); the server ignores `all` for everybody else. */
        @Query("scope") scope: String? = null,
    ): V2ListEnvelope<YarnListItemDto>

    @GET("api/v2/yarns/{yarnId}")
    suspend fun getYarn(@Path("yarnId") yarnId: Int): V2Envelope<YarnV2Dto>

    @POST("api/v2/yarns")
    suspend fun createYarn(@Body request: YarnWriteRequest): V2Envelope<YarnV2Dto>

    @PUT("api/v2/yarns/{yarnId}")
    suspend fun updateYarn(
        @Path("yarnId") yarnId: Int,
        @Body request: YarnWriteRequest,
    ): V2Envelope<YarnV2Dto>

    @DELETE("api/v2/yarns/{yarnId}") suspend fun deleteYarn(@Path("yarnId") yarnId: Int)

    /** v1 used POST here; v2 uses PUT (idempotent). */
    @PUT("api/v2/yarns/{yarnId}/favorite")
    suspend fun favoriteYarn(@Path("yarnId") yarnId: Int): V2Envelope<YarnV2Dto>

    @DELETE("api/v2/yarns/{yarnId}/favorite")
    suspend fun unfavoriteYarn(@Path("yarnId") yarnId: Int): V2Envelope<YarnV2Dto>

    @Multipart
    @POST("api/v2/yarns/{yarnId}/photos")
    suspend fun uploadPhoto(
        @Path("yarnId") yarnId: Int,
        @Part file: MultipartBody.Part,
    ): V2Envelope<YarnPhotoDto>

    @DELETE("api/v2/yarns/{yarnId}/photos/{photoId}")
    suspend fun deletePhoto(@Path("yarnId") yarnId: Int, @Path("photoId") photoId: Int)

    @PUT("api/v2/yarns/{yarnId}/photos/order")
    suspend fun reorderPhotos(
        @Path("yarnId") yarnId: Int,
        @Body request: MediaOrderRequest,
    ): V2Envelope<MediaOrderDto>
}
