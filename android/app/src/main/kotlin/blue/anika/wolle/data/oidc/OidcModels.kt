package blue.anika.wolle.data.oidc

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire types for `docs/oidc-v2-contract.md`. `Json` in `NetworkModule` does not encode defaults, so
 * constant request fields (`client`, `code_challenge_method`) are passed explicitly by the builders
 * below instead of relying on default values.
 */

/** The only return URI the app registers and sends: a custom scheme, see the manifest. */
const val OIDC_REDIRECT_URI = "stricknani://oidc-callback"

const val OIDC_CLIENT_ANDROID = "android"
const val OIDC_CHALLENGE_METHOD = "S256"

/** `auth_origin` value of a PAT minted through OIDC. */
const val AUTH_ORIGIN_OIDC = "oidc"

/** One entry of `data.auth.oidc` in `GET /api/v2/meta`. */
@Serializable data class OidcProvider(val id: String, val name: String)

@Serializable
data class OidcStartResponse(
    @SerialName("authorization_url") val authorizationUrl: String,
    @SerialName("expires_at") val expiresAt: String? = null,
)

/** Native start body, shared by login, link and reauthentication. */
@Serializable
data class OidcStartRequest(
    val provider: String,
    val client: String,
    @SerialName("redirect_uri") val redirectUri: String,
    @SerialName("app_state") val appState: String,
    @SerialName("code_challenge") val codeChallenge: String,
    @SerialName("code_challenge_method") val codeChallengeMethod: String,
)

/** `POST /account/reauth/oidc/start`: the native start fields plus what is being authorized. */
@Serializable
data class OidcReauthStartRequest(
    val provider: String,
    val action: String,
    val target: ReauthTargetDto? = null,
    val client: String,
    @SerialName("redirect_uri") val redirectUri: String,
    @SerialName("app_state") val appState: String,
    @SerialName("code_challenge") val codeChallenge: String,
    @SerialName("code_challenge_method") val codeChallengeMethod: String,
)

@Serializable
data class OidcLoginExchangeRequest(
    val code: String,
    @SerialName("code_verifier") val codeVerifier: String,
    @SerialName("redirect_uri") val redirectUri: String,
    @SerialName("token_name") val tokenName: String? = null,
    /** Names the minted token `Android · <device_name>` on a server that knows the field. */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("device_name")
    val deviceName: String? = null,
)

/** Link and reauthentication exchange: no token name, nothing is minted. */
@Serializable
data class OidcGrantExchangeRequest(
    val code: String,
    @SerialName("code_verifier") val codeVerifier: String,
    @SerialName("redirect_uri") val redirectUri: String,
)

@Serializable
data class OidcLoginExchangeResponse(
    val token: String,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("auth_origin") val authOrigin: String? = null,
)

/** `GET /account/identities` and its admin counterpart. */
@Serializable
data class OidcIdentity(
    val id: Long,
    val provider: String,
    @SerialName("subject_hint") val subjectHint: String = "",
    val email: String? = null,
    @SerialName("linked_at") val linkedAt: String? = null,
    @SerialName("can_unlink") val canUnlink: Boolean = true,
)

@Serializable
data class IdentitiesMeta(@SerialName("password_enabled") val passwordEnabled: Boolean = true)

@Serializable
data class IdentitiesEnvelope(
    val data: List<OidcIdentity> = emptyList(),
    val meta: IdentitiesMeta = IdentitiesMeta(),
)

@Serializable data class LinkResultDto(val identity: OidcIdentity)

@Serializable
data class PasswordReauthRequest(
    val password: String,
    val action: String,
    val target: ReauthTargetDto? = null,
)

@Serializable
data class ReauthTokenDto(
    @SerialName("reauth_token") val reauthToken: String,
    @SerialName("expires_at") val expiresAt: String? = null,
)

/** `target` of a reauthentication: only the fields that apply to the action. */
@Serializable
data class ReauthTargetDto(
    val provider: String? = null,
    @SerialName("identity_id") val identityId: Long? = null,
    @SerialName("user_id") val userId: Long? = null,
)

/** The step-up actions this client asks for (a subset of the contract's list). */
enum class ReauthAction(val wire: String) {
    LINK_IDENTITY("link_identity"),
    UNLINK_IDENTITY("unlink_identity"),
    ADMIN_UNLINK_IDENTITY("admin_unlink_identity"),
    DELETE_ACCOUNT("delete_account"),
    CREATE_API_TOKEN("create_api_token"),
    RESTORE_BACKUP("restore_backup"),
}

/** What a reauthentication proof is bound to; the server rejects any other combination. */
sealed interface ReauthTarget {
    /** Null for an action that takes no target (the server rejects an empty `target` object). */
    fun toDto(): ReauthTargetDto?

    /** Actions on the account itself: nothing to point at. */
    data object None : ReauthTarget {
        override fun toDto(): ReauthTargetDto? = null
    }

    data class Provider(val provider: String) : ReauthTarget {
        override fun toDto() = ReauthTargetDto(provider = provider)
    }

    data class Identity(val identityId: Long) : ReauthTarget {
        override fun toDto() = ReauthTargetDto(identityId = identityId)
    }

    data class AdminIdentity(val userId: Long, val identityId: Long) : ReauthTarget {
        override fun toDto() = ReauthTargetDto(identityId = identityId, userId = userId)
    }
}

/**
 * A one-use proof for [action] and [target]. Held in memory only, sent only as `X-Reauth-Token`.
 * [toString] never prints the token.
 */
class ReauthProof(val action: ReauthAction, val target: ReauthTarget, val token: String) {
    override fun toString() = "ReauthProof($action, $target)"
}
