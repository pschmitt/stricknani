package blue.anika.wolle.data.oidc

import blue.anika.wolle.data.api.v2.V2Envelope
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Authenticated OIDC account calls (`docs/oidc-v2-contract.md`), made with the stored PAT through
 * the app's normal client. `X-Reauth-Token` is a one-use step-up proof; it is passed per call and
 * never logged (the HTTP logger runs at BASIC, which prints no headers).
 */
interface OidcAccountApi {
    @GET("api/v2/account/identities") suspend fun identities(): IdentitiesEnvelope

    @POST("api/v2/account/reauth")
    suspend fun reauthWithPassword(@Body request: PasswordReauthRequest): V2Envelope<ReauthTokenDto>

    @POST("api/v2/account/reauth/oidc/start")
    suspend fun reauthOidcStart(
        @Body request: OidcReauthStartRequest
    ): V2Envelope<OidcStartResponse>

    @POST("api/v2/account/reauth/oidc/exchange")
    suspend fun reauthOidcExchange(
        @Body request: OidcGrantExchangeRequest
    ): V2Envelope<ReauthTokenDto>

    @POST("api/v2/account/identities/link")
    suspend fun linkStart(
        @Header("X-Reauth-Token") reauthToken: String,
        @Body request: OidcStartRequest,
    ): V2Envelope<OidcStartResponse>

    @POST("api/v2/account/identities/link/exchange")
    suspend fun linkExchange(
        @Header("X-Reauth-Token") reauthToken: String,
        @Body request: OidcGrantExchangeRequest,
    ): V2Envelope<LinkResultDto>

    @DELETE("api/v2/account/identities/{id}")
    suspend fun unlink(@Path("id") identityId: Long, @Header("X-Reauth-Token") reauthToken: String)

    @GET("api/v2/admin/users/{userId}/identities")
    suspend fun adminIdentities(@Path("userId") userId: Long): IdentitiesEnvelope

    @DELETE("api/v2/admin/users/{userId}/identities/{id}")
    suspend fun adminUnlink(
        @Path("userId") userId: Long,
        @Path("id") identityId: Long,
        @Header("X-Reauth-Token") reauthToken: String,
    )
}
