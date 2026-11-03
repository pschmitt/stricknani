package blue.anika.wolle.data.api.v2

import blue.anika.wolle.data.api.dto.ActivityPageDto
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * `rust/src/api_v2/activity.rs`: the Home "Recent activity" feed. Online-only. A 404/405 means the
 * server does not have the endpoint yet.
 */
interface ActivityV2Api {
    @GET("api/v2/activity")
    suspend fun activity(
        @Query("limit") limit: Int,
        @Query("before") before: Long? = null,
    ): ActivityPageDto
}
