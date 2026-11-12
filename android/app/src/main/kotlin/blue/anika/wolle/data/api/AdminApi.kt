package blue.anika.wolle.data.api

import blue.anika.wolle.data.api.dto.AdminUserEnvelopeDto
import blue.anika.wolle.data.api.dto.AdminUsersPageDto
import blue.anika.wolle.data.api.dto.AuditDetailEnvelopeDto
import blue.anika.wolle.data.api.dto.AuditPageDto
import blue.anika.wolle.data.api.dto.CreateAdminUserRequest
import blue.anika.wolle.data.api.dto.ImpersonationStartEnvelopeDto
import blue.anika.wolle.data.api.dto.ImpersonationStopEnvelopeDto
import blue.anika.wolle.data.api.dto.ResetPasswordEnvelopeDto
import blue.anika.wolle.data.api.dto.ResetPasswordRequest
import blue.anika.wolle.data.api.dto.SetUserActiveRequest
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** `src/api_v2/{audit,admin}.rs` - administrator-only, online-only. */
interface AdminApi {
    /**
     * [entity] is `project`, `yarn` or `category`; null means all. Null query values are omitted.
     */
    @GET("api/v2/admin/audit")
    suspend fun listAudit(
        @Query("page") page: Int,
        @Query("entity") entity: String? = null,
        @Query("action") action: String? = null,
        @Query("user") user: Long? = null,
    ): AuditPageDto

    @GET("api/v2/admin/audit/{id}")
    suspend fun getAuditEvent(@Path("id") id: Long): AuditDetailEnvelopeDto

    @GET("api/v2/admin/users") suspend fun listUsers(@Query("page") page: Int): AdminUsersPageDto

    @POST("api/v2/admin/users")
    suspend fun createUser(@Body request: CreateAdminUserRequest): AdminUserEnvelopeDto

    @PATCH("api/v2/admin/users/{id}/active")
    suspend fun setActive(
        @Path("id") id: Long,
        @Body request: SetUserActiveRequest,
    ): AdminUserEnvelopeDto

    @POST("api/v2/admin/users/{id}/reset-password")
    suspend fun resetPassword(
        @Path("id") id: Long,
        @Body request: ResetPasswordRequest,
    ): ResetPasswordEnvelopeDto

    /**
     * Asks for a short-lived credential that acts as [id]. Sent with the administrator's own token;
     * the server refuses anything else.
     */
    @POST("api/v2/admin/users/{id}/impersonation-token")
    suspend fun startImpersonation(@Path("id") id: Long): ImpersonationStartEnvelopeDto

    /**
     * Revokes the impersonation credential this request carries (or, sent with the administrator's
     * own token, every impersonation of theirs).
     */
    @POST("api/v2/admin/impersonation/stop")
    suspend fun stopImpersonation(): ImpersonationStopEnvelopeDto
}
