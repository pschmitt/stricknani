package blue.anika.wolle.data.passkey

import blue.anika.wolle.data.oidc.ReauthAction
import blue.anika.wolle.data.oidc.ReauthProof
import blue.anika.wolle.data.oidc.ReauthTarget
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Everything the screens do with passkeys. Failures are [Result.failure] carrying a
 * [PasskeyException]; read them with [passkeyFailure]. The view models depend on this interface so
 * their flows are tested with a fake.
 */
interface PasskeyService {
    /** The server advertises passkeys in its public metadata. */
    suspend fun available(serverUrl: String): Boolean

    /**
     * Username-less sign-in against [serverUrl]: the PAT for the account the passkey belongs to.
     */
    suspend fun signIn(host: PasskeyHost, serverUrl: String, deviceName: String): Result<String>

    suspend fun list(): Result<PasskeyList>

    /** Creates a passkey on this device; [proof] is a `manage_passkeys` step-up proof. */
    suspend fun register(
        host: PasskeyHost,
        nickname: String,
        proof: ReauthProof,
    ): Result<PasskeyDto>

    suspend fun rename(id: Long, nickname: String): Result<PasskeyDto>

    suspend fun delete(id: Long, proof: ReauthProof): Result<Unit>

    /** Confirms [action] with one of the account's passkeys: a one-use proof for it. */
    suspend fun reauth(
        host: PasskeyHost,
        action: ReauthAction,
        target: ReauthTarget,
    ): Result<ReauthProof>
}

@Singleton
class PasskeyRepository
@Inject
constructor(
    private val api: PasskeyApi,
    private val loginApi: PasskeyLoginApi,
    private val gateway: PasskeyGateway,
    private val json: Json,
) : PasskeyService {

    override suspend fun available(serverUrl: String): Boolean = loginApi.available(serverUrl)

    override suspend fun signIn(
        host: PasskeyHost,
        serverUrl: String,
        deviceName: String,
    ): Result<String> = call {
        val challenge = loginApi.options(serverUrl)
        val assertion = gateway.get(host, requestJson(challenge)).orThrow()
        loginApi.exchange(serverUrl, challenge.challengeId, parse(assertion), deviceName)
    }

    override suspend fun list(): Result<PasskeyList> = call {
        val envelope = api.list()
        PasskeyList(envelope.data, envelope.meta.limit)
    }

    override suspend fun register(
        host: PasskeyHost,
        nickname: String,
        proof: ReauthProof,
    ): Result<PasskeyDto> = call {
        requireProof(proof, ReauthAction.MANAGE_PASSKEYS)
        val challenge = api.registrationOptions(proof.token, JsonObject(emptyMap())).data
        val registration = gateway.create(host, requestJson(challenge)).orThrow()
        api.registrationVerify(
                proof.token,
                PasskeyRegisterRequest(challenge.challengeId, parse(registration), nickname),
            )
            .data
    }

    override suspend fun rename(id: Long, nickname: String): Result<PasskeyDto> = call {
        api.rename(id, PasskeyRenameRequest(nickname)).data
    }

    override suspend fun delete(id: Long, proof: ReauthProof): Result<Unit> = call {
        requireProof(proof, ReauthAction.MANAGE_PASSKEYS)
        api.delete(id, proof.token)
    }

    override suspend fun reauth(
        host: PasskeyHost,
        action: ReauthAction,
        target: ReauthTarget,
    ): Result<ReauthProof> = call {
        val challenge = api.reauthOptions(JsonObject(emptyMap())).data
        val assertion = gateway.get(host, requestJson(challenge)).orThrow()
        val token =
            api.reauth(
                    PasskeyReauthRequest(
                        challenge.challengeId,
                        parse(assertion),
                        action.wire,
                        target.toDto(),
                    )
                )
                .data
                .reauthToken
        ReauthProof(action, target, token)
    }

    /** The server's `options.publicKey`, which is what Credential Manager takes as request JSON. */
    private fun requestJson(challenge: ChallengeDto): String =
        challenge.options["publicKey"]?.jsonObject?.toString()
            ?: throw PasskeyException(PasskeyFailure.Other(null))

    private fun parse(credentialJson: String) =
        try {
            json.parseToJsonElement(credentialJson)
        } catch (_: Exception) {
            throw PasskeyException(PasskeyFailure.Other(null))
        }

    private fun requireProof(proof: ReauthProof, action: ReauthAction) {
        if (proof.action != action) throw PasskeyException(PasskeyFailure.ReauthRequired)
    }

    private fun PasskeyOutcome.orThrow(): String =
        when (this) {
            is PasskeyOutcome.Success -> json
            is PasskeyOutcome.Failed -> throw PasskeyException(failure)
        }

    private suspend fun <T> call(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Result.failure(PasskeyException(failure.toPasskeyFailure()))
        }
}
