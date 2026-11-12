package blue.anika.wolle.data.passkey

import blue.anika.wolle.data.oidc.ReauthTargetDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Wire types of the passkey routes (`docs/web-v2-contract.md`, "Passkeys (WebAuthn)"). */

/** A passkey of the signed-in account, as listed. Nothing here is secret. */
@Serializable
data class PasskeyDto(
    val id: Long,
    val nickname: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("last_used_at") val lastUsedAt: String? = null,
    val transports: List<String> = emptyList(),
    /** Can be synced between devices (a "multi-device" passkey). */
    @SerialName("backup_eligible") val backupEligible: Boolean = false,
    /** Is synced right now. */
    @SerialName("backup_state") val backupState: Boolean = false,
)

@Serializable data class PasskeyListMeta(val limit: Int = DEFAULT_PASSKEY_LIMIT)

@Serializable
data class PasskeyListEnvelope(
    val data: List<PasskeyDto> = emptyList(),
    val meta: PasskeyListMeta = PasskeyListMeta(),
)

/** What the account's passkey list looks like to the screens. */
data class PasskeyList(val passkeys: List<PasskeyDto>, val limit: Int)

const val DEFAULT_PASSKEY_LIMIT = 10

/**
 * A ceremony the server started: the opaque id to send back and the WebAuthn options. The options
 * are `{"publicKey": {...}}`; [PasskeyOptions.requestJson] is what Credential Manager wants.
 */
@Serializable
data class ChallengeDto(
    @SerialName("challenge_id") val challengeId: String,
    val options: JsonObject,
)

/** `POST .../registration/verify`. The credential is Credential Manager's JSON, passed through. */
@Serializable
data class PasskeyRegisterRequest(
    @SerialName("challenge_id") val challengeId: String,
    val credential: JsonElement,
    val nickname: String,
)

@Serializable data class PasskeyRenameRequest(val nickname: String)

/** `POST /account/reauth/passkey`: the assertion plus what is being authorized. */
@Serializable
data class PasskeyReauthRequest(
    @SerialName("challenge_id") val challengeId: String,
    val credential: JsonElement,
    val action: String,
    val target: ReauthTargetDto? = null,
)

/** `POST /auth/passkey/token`. */
@Serializable
data class PasskeyTokenRequest(
    @SerialName("challenge_id") val challengeId: String,
    val credential: JsonElement,
    @SerialName("device_name") val deviceName: String,
)

@Serializable data class PasskeyTokenResponse(val token: String)

/** `data.auth.passkeys` of `GET /api/v2/meta`. */
@Serializable internal data class MetaPasskeyAuthDto(val passkeys: Boolean = false)

@Serializable internal data class MetaPasskeyDto(val auth: MetaPasskeyAuthDto? = null)
