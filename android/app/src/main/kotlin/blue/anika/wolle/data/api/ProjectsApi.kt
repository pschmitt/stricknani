package blue.anika.wolle.data.api

import blue.anika.wolle.data.api.dto.ProjectDto
import blue.anika.wolle.data.api.dto.ProjectPageDto
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * `stricknani/routes/api/projects.py`. Read (list/detail) + favorite toggle - favorite/unfavorite
 * are simple, idempotent, single-field calls made directly online (with local optimistic-update +
 * rollback in `ProjectRepository`) rather than waiting on the general offline write queue (SNA-8).
 * Create/update/delete/steps/images/attachments are SNA-8/SNA-10.
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

    @POST("api/v1/projects/{projectId}/favorite")
    suspend fun favoriteProject(@Path("projectId") projectId: Int): ProjectDto

    @DELETE("api/v1/projects/{projectId}/favorite")
    suspend fun unfavoriteProject(@Path("projectId") projectId: Int): ProjectDto
}
