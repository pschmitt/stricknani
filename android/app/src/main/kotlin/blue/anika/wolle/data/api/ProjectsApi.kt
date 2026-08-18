package blue.anika.wolle.data.api

import blue.anika.wolle.data.api.dto.ProjectDto
import blue.anika.wolle.data.api.dto.ProjectPageDto
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * `stricknani/routes/api/projects.py`. Only the read (list/detail) surface used by SNA-7's sync
 * engine - write/upload endpoints (create/update/delete/favorite/steps/images/attachments) are
 * SNA-8/SNA-10.
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
}
