package blue.anika.wolle.data.api.v2

import blue.anika.wolle.data.api.dto.AttachmentDto
import blue.anika.wolle.data.api.dto.ImageDto
import blue.anika.wolle.data.api.dto.ProjectListItemDto
import blue.anika.wolle.data.api.dto.ProjectWriteRequest
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * `rust/src/api_v2/{projects,media}.rs`. NOT wired into any repository yet (SNA-87): the server
 * only accepts a browser session cookie on these routes today; Bearer PAT support is pending. Kept
 * 1:1 with `ProjectsApi` so a repository can switch by changing the injected type and calling the
 * mappers in `V2Mappers.kt`.
 */
interface ProjectsV2Api {
    @GET("api/v2/projects")
    suspend fun listProjects(
        @Query("page") page: Int = 1,
        @Query("category") category: String? = null,
        @Query("tag") tag: String? = null,
        @Query("favorite") favorite: Boolean? = null,
        @Query("q") query: String? = null,
    ): V2ListEnvelope<ProjectListItemDto>

    @GET("api/v2/projects/{projectId}")
    suspend fun getProject(@Path("projectId") projectId: Int): V2Envelope<ProjectV2Dto>

    @POST("api/v2/projects")
    suspend fun createProject(@Body request: ProjectWriteRequest): V2Envelope<ProjectV2Dto>

    @PUT("api/v2/projects/{projectId}")
    suspend fun updateProject(
        @Path("projectId") projectId: Int,
        @Body request: ProjectWriteRequest,
    ): V2Envelope<ProjectV2Dto>

    @DELETE("api/v2/projects/{projectId}")
    suspend fun deleteProject(@Path("projectId") projectId: Int)

    /** v1 used POST here; v2 uses PUT (idempotent). */
    @PUT("api/v2/projects/{projectId}/favorite")
    suspend fun favoriteProject(@Path("projectId") projectId: Int): V2Envelope<ProjectV2Dto>

    @DELETE("api/v2/projects/{projectId}/favorite")
    suspend fun unfavoriteProject(@Path("projectId") projectId: Int): V2Envelope<ProjectV2Dto>

    @Multipart
    @POST("api/v2/projects/{projectId}/images/title")
    suspend fun uploadTitleImage(
        @Path("projectId") projectId: Int,
        @Part file: MultipartBody.Part,
        @Part("alt_text") altText: RequestBody? = null,
    ): V2Envelope<ImageDto>

    @Multipart
    @POST("api/v2/projects/{projectId}/steps/{stepId}/images")
    suspend fun uploadStepImage(
        @Path("projectId") projectId: Int,
        @Path("stepId") stepId: Int,
        @Part file: MultipartBody.Part,
        @Part("alt_text") altText: RequestBody? = null,
    ): V2Envelope<ImageDto>

    @Multipart
    @POST("api/v2/projects/{projectId}/attachments")
    suspend fun uploadAttachment(
        @Path("projectId") projectId: Int,
        @Part file: MultipartBody.Part,
    ): V2Envelope<AttachmentDto>

    @DELETE("api/v2/projects/{projectId}/attachments/{attachmentId}")
    suspend fun deleteAttachment(
        @Path("projectId") projectId: Int,
        @Path("attachmentId") attachmentId: Int,
    )

    @DELETE("api/v2/projects/{projectId}/images/{imageId}")
    suspend fun deleteImage(@Path("projectId") projectId: Int, @Path("imageId") imageId: Int)

    /** Title images only; `ids` is the complete desired order. 422: not exactly the group's ids. */
    @PUT("api/v2/projects/{projectId}/images/order")
    suspend fun reorderTitleImages(
        @Path("projectId") projectId: Int,
        @Body request: MediaOrderRequest,
    ): V2Envelope<MediaOrderDto>

    /** One step's images; 404 when the step is not the project's. */
    @PUT("api/v2/projects/{projectId}/steps/{stepId}/images/order")
    suspend fun reorderStepImages(
        @Path("projectId") projectId: Int,
        @Path("stepId") stepId: Int,
        @Body request: MediaOrderRequest,
    ): V2Envelope<MediaOrderDto>
}
