package blue.anika.wolle.data.passkey

import blue.anika.wolle.data.api.v2.V2Envelope
import blue.anika.wolle.data.oidc.ReauthTokenDto
import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Authenticated passkey calls, made with the stored PAT through the app's normal client.
 * `X-Reauth-Token` is a one-use step-up proof for `manage_passkeys`; it is passed per call and
 * never logged. The request bodies of the `*Options` calls are empty JSON objects.
 */
interface PasskeyApi {
    @GET("api/v2/account/passkeys") suspend fun list(): PasskeyListEnvelope

    @POST("api/v2/account/passkeys/registration/options")
    suspend fun registrationOptions(
        @Header("X-Reauth-Token") reauthToken: String,
        @Body body: JsonObject,
    ): V2Envelope<ChallengeDto>

    @POST("api/v2/account/passkeys/registration/verify")
    suspend fun registrationVerify(
        @Header("X-Reauth-Token") reauthToken: String,
        @Body request: PasskeyRegisterRequest,
    ): V2Envelope<PasskeyDto>

    @PATCH("api/v2/account/passkeys/{id}")
    suspend fun rename(
        @Path("id") id: Long,
        @Body request: PasskeyRenameRequest,
    ): V2Envelope<PasskeyDto>

    @DELETE("api/v2/account/passkeys/{id}")
    suspend fun delete(@Path("id") id: Long, @Header("X-Reauth-Token") reauthToken: String)

    @POST("api/v2/account/reauth/passkey/options")
    suspend fun reauthOptions(@Body body: JsonObject): V2Envelope<ChallengeDto>

    @POST("api/v2/account/reauth/passkey")
    suspend fun reauth(@Body request: PasskeyReauthRequest): V2Envelope<ReauthTokenDto>
}
