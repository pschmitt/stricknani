package blue.anika.wolle.data.api

import blue.anika.wolle.data.api.dto.ImageDto
import blue.anika.wolle.data.api.dto.ProjectDto
import blue.anika.wolle.data.api.dto.ProjectPageDto
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
 * `stricknani/routes/api/projects.py`. Read (list/detail), favorite toggle (simple, idempotent,
 * single-field, called directly online with local optimistic-update + rollback in
 * `ProjectRepository` rather than through the offline write queue), and create/update/delete
 * (queued through `PendingMutationDao`/`sync/WriteReplayWorker.kt` - SNA-8). Multipart image
 * uploads are also exposed here so the same outbox can replay them after a project create/update.
 */
interface ProjectsApi {
    @GET("api/v1/projects")
    suspend fun listProjects(
        @Query("page") page: Int = 1,
        @Query("category") category: String? = null,
        @Query("tag") tag: String? = null,
        @Query("favorite") favorite: Boolean? = null,
    ): ProjectPageDto

    @GET("api/v1/projects/{projectId}")
    suspend fun getProject(@Path("projectId") projectId: Int): ProjectDto

    @POST("api/v1/projects")
    suspend fun createProject(@Body request: ProjectWriteRequest): ProjectDto

    @PUT("api/v1/projects/{projectId}")
    suspend fun updateProject(
        @Path("projectId") projectId: Int,
        @Body request: ProjectWriteRequest,
    ): ProjectDto

    @DELETE("api/v1/projects/{projectId}")
    suspend fun deleteProject(@Path("projectId") projectId: Int)

    @POST("api/v1/projects/{projectId}/favorite")
    suspend fun favoriteProject(@Path("projectId") projectId: Int): ProjectDto

    @DELETE("api/v1/projects/{projectId}/favorite")
    suspend fun unfavoriteProject(@Path("projectId") projectId: Int): ProjectDto

    @Multipart
    @POST("api/v1/projects/{projectId}/images/title")
    suspend fun uploadTitleImage(
        @Path("projectId") projectId: Int,
        @Part file: MultipartBody.Part,
        @Part("alt_text") altText: RequestBody? = null,
    ): ImageDto

    @Multipart
    @POST("api/v1/projects/{projectId}/steps/{stepId}/images")
    suspend fun uploadStepImage(
        @Path("projectId") projectId: Int,
        @Path("stepId") stepId: Int,
        @Part file: MultipartBody.Part,
        @Part("alt_text") altText: RequestBody? = null,
    ): ImageDto
}
