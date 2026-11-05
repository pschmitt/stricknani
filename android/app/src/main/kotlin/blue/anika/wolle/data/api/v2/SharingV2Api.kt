package blue.anika.wolle.data.api.v2

import blue.anika.wolle.data.api.dto.PeoplePageDto
import blue.anika.wolle.data.api.dto.ShareCreateRequest
import blue.anika.wolle.data.api.dto.ShareDto
import blue.anika.wolle.data.api.dto.ShareListDto
import blue.anika.wolle.data.api.dto.ShareRoleRequest
import blue.anika.wolle.data.api.dto.TransferRequest
import blue.anika.wolle.data.api.dto.TransferResultDto
import blue.anika.wolle.data.api.dto.YarnCandidatesPageDto
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Sharing and ownership transfer, `docs/sharing-api.md`. `{kind}` is `projects` or `yarns`. All
 * calls need the network: sharing is never queued. Success bodies are `{ "data": ... }`.
 */
interface SharingV2Api {
    @GET("api/v2/{kind}/{id}/shares")
    suspend fun listShares(
        @Path("kind") kind: String,
        @Path("id") id: Int,
    ): V2Envelope<ShareListDto>

    @POST("api/v2/{kind}/{id}/shares")
    suspend fun addShare(
        @Path("kind") kind: String,
        @Path("id") id: Int,
        @Body request: ShareCreateRequest,
    ): V2Envelope<ShareDto>

    @PATCH("api/v2/{kind}/{id}/shares/{userId}")
    suspend fun changeRole(
        @Path("kind") kind: String,
        @Path("id") id: Int,
        @Path("userId") userId: Long,
        @Body request: ShareRoleRequest,
    ): V2Envelope<ShareDto>

    @DELETE("api/v2/{kind}/{id}/shares/{userId}")
    suspend fun revoke(
        @Path("kind") kind: String,
        @Path("id") id: Int,
        @Path("userId") userId: Long,
    )

    @POST("api/v2/{kind}/{id}/leave")
    suspend fun leave(@Path("kind") kind: String, @Path("id") id: Int)

    @POST("api/v2/{kind}/{id}/transfer")
    suspend fun transfer(
        @Path("kind") kind: String,
        @Path("id") id: Int,
        @Body request: TransferRequest,
    ): V2Envelope<TransferResultDto>

    @GET("api/v2/projects/{id}/yarn-candidates")
    suspend fun yarnCandidates(
        @Path("id") id: Int,
        @Query("q") query: String? = null,
        @Query("page") page: Int = 1,
    ): V2Envelope<YarnCandidatesPageDto>

    @GET("api/v2/people")
    suspend fun people(
        @Query("q") query: String? = null,
        @Query("page") page: Int = 1,
    ): V2Envelope<PeoplePageDto>
}
