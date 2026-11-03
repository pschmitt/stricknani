package blue.anika.wolle.data.api

import blue.anika.wolle.data.api.dto.AuditDetailEnvelopeDto
import blue.anika.wolle.data.api.dto.AuditPageDto
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The owner-visible change history of one project or yarn
 * (`/api/v2/{projects|yarns}/{id}/history`). Same event and change-set shapes as the administrator
 * audit log; 20 events per page, newest first. Online-only. A 404/405 means the server does not
 * have the endpoint yet.
 */
interface HistoryApi {
    @GET("api/v2/projects/{id}/history")
    suspend fun projectHistory(@Path("id") id: Int, @Query("page") page: Int): AuditPageDto

    @GET("api/v2/projects/{id}/history/{eventId}")
    suspend fun projectHistoryEvent(
        @Path("id") id: Int,
        @Path("eventId") eventId: Long,
    ): AuditDetailEnvelopeDto

    @GET("api/v2/yarns/{id}/history")
    suspend fun yarnHistory(@Path("id") id: Int, @Query("page") page: Int): AuditPageDto

    @GET("api/v2/yarns/{id}/history/{eventId}")
    suspend fun yarnHistoryEvent(
        @Path("id") id: Int,
        @Path("eventId") eventId: Long,
    ): AuditDetailEnvelopeDto
}
