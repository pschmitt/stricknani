package blue.anika.wolle.data.api

import blue.anika.wolle.data.api.dto.CategorySyncResponseDto
import blue.anika.wolle.data.api.dto.ProjectSyncResponseDto
import blue.anika.wolle.data.api.dto.YarnSyncResponseDto
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * `stricknani/routes/api/sync.py`. `since` is an ISO8601 timestamp string (the previous response's
 * `serverTime`, passed through verbatim - never reformatted client-side) or `null` for a first/full
 * sync.
 */
interface SyncApi {
    @GET("api/v1/sync/projects")
    suspend fun syncProjects(@Query("since") since: String? = null): ProjectSyncResponseDto

    @GET("api/v1/sync/yarns")
    suspend fun syncYarns(@Query("since") since: String? = null): YarnSyncResponseDto

    @GET("api/v1/sync/categories")
    suspend fun syncCategories(@Query("since") since: String? = null): CategorySyncResponseDto
}
