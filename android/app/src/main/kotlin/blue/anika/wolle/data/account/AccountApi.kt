package blue.anika.wolle.data.account

import blue.anika.wolle.data.api.v2.V2Envelope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path

/**
 * `rust/src/api_v2/account.rs` (`docs/web-v2-contract.md`, "Bearer PAT access"): the account
 * self-service routes a bearer PAT may use. Servers without that change answer 401/404/405, which
 * [AccountRepository] maps to "feature unavailable".
 */
interface AccountV2Api {
    @GET("api/v2/account/api-tokens") suspend fun tokens(): V2Envelope<List<ApiTokenDto>>

    /** A bearer caller must send `current_password` (step-up), otherwise 403 `reauth_required`. */
    @POST("api/v2/account/api-tokens")
    suspend fun createToken(@Body request: CreateTokenRequest): V2Envelope<CreatedTokenDto>

    /** 200 `{id, revoked, current_token}` for a bearer caller; 204 for a browser. */
    @DELETE("api/v2/account/api-tokens/{id}")
    suspend fun revokeToken(@Path("id") id: Long): Response<V2Envelope<RevokedTokenDto>>

    /** 204; the server revokes every session and token of the account, this one included. */
    @PUT("api/v2/account/password") suspend fun changePassword(@Body request: ChangePasswordRequest)

    @Multipart
    @POST("api/v2/account/avatar")
    suspend fun uploadAvatar(@Part file: MultipartBody.Part): V2Envelope<AvatarDto>

    @DELETE("api/v2/account/avatar") suspend fun deleteAvatar()
}

/** One row of `GET /account/api-tokens`: never carries the secret. */
@Serializable
data class ApiTokenDto(
    val id: Long,
    val name: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("expires_at") val expiresAt: String? = null,
)

/** `201` body of a token creation: [token] is the secret, shown exactly once. */
@Serializable
data class CreatedTokenDto(
    val id: Long,
    val name: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("expires_at") val expiresAt: String? = null,
    val token: String,
) {
    /** The list row for this token (what a later list call would return). */
    fun summary() = ApiTokenDto(id, name, createdAt, expiresAt)

    /** Keeps the secret out of logs and crash reports. */
    override fun toString(): String = "CreatedTokenDto(id=$id, name=$name, token=<redacted>)"
}

@Serializable
data class RevokedTokenDto(
    val id: Long,
    val revoked: Boolean = true,
    /** True when the caller revoked the very token it is using: it must sign out locally. */
    @SerialName("current_token") val currentToken: Boolean = false,
)

@Serializable
data class CreateTokenRequest(
    val name: String,
    @SerialName("current_password") val currentPassword: String,
) {
    override fun toString(): String = "CreateTokenRequest(name=$name)"
}

@Serializable
data class ChangePasswordRequest(
    @SerialName("current_password") val currentPassword: String,
    @SerialName("new_password") val newPassword: String,
) {
    override fun toString(): String = "ChangePasswordRequest()"
}

@Serializable data class AvatarDto(@SerialName("avatar_url") val avatarUrl: String)
