package blue.anika.wolle.data.passkey

import blue.anika.wolle.data.api.v2.V2Envelope
import blue.anika.wolle.data.oidc.ReauthTokenDto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A host that is never asked for a window: the fake gateway needs none. */
internal val NoWindow = PasskeyHost { error("unit tests have no window") }

/** A recording [PasskeyGateway]: the sheet shows what the test scripts. */
internal class FakeGateway : PasskeyGateway {
    var created: PasskeyOutcome = PasskeyOutcome.Success("""{"id":"new","type":"public-key"}""")
    var got: PasskeyOutcome = PasskeyOutcome.Success("""{"id":"sig","type":"public-key"}""")
    val createRequests = mutableListOf<String>()
    val getRequests = mutableListOf<String>()

    override suspend fun create(host: PasskeyHost, requestJson: String): PasskeyOutcome {
        createRequests += requestJson
        return created
    }

    override suspend fun get(host: PasskeyHost, requestJson: String): PasskeyOutcome {
        getRequests += requestJson
        return got
    }
}

internal fun challenge(id: String = "c-1") =
    ChallengeDto(
        id,
        buildJsonObject {
            put(
                "publicKey",
                buildJsonObject {
                    put("challenge", JsonPrimitive("AAEC"))
                    put("rpId", JsonPrimitive("wolle.example.org"))
                },
            )
        },
    )

internal fun passkey(id: Long = 1, name: String = "Pixel", lastUsed: String? = null) =
    PasskeyDto(id, name, "2026-11-12T07:00:00.000000Z", lastUsed)

/** A scriptable [PasskeyApi]. A set [error] fails every call. */
internal class FakePasskeyApi : PasskeyApi {
    var error: Exception? = null
    var passkeys = mutableListOf(passkey())
    val calls = mutableListOf<String>()
    var registered: PasskeyRegisterRequest? = null
    var reauthRequest: PasskeyReauthRequest? = null
    val reauthTokens = mutableListOf<String>()

    private fun check(name: String) {
        calls += name
        error?.let { throw it }
    }

    override suspend fun list(): PasskeyListEnvelope {
        check("list")
        return PasskeyListEnvelope(passkeys.toList(), PasskeyListMeta(10))
    }

    override suspend fun registrationOptions(
        reauthToken: String,
        body: JsonObject,
    ): V2Envelope<ChallengeDto> {
        check("registrationOptions")
        reauthTokens += reauthToken
        return V2Envelope(challenge("reg-1"))
    }

    override suspend fun registrationVerify(
        reauthToken: String,
        request: PasskeyRegisterRequest,
    ): V2Envelope<PasskeyDto> {
        check("registrationVerify")
        reauthTokens += reauthToken
        registered = request
        return V2Envelope(passkey(9, request.nickname))
    }

    override suspend fun rename(id: Long, request: PasskeyRenameRequest): V2Envelope<PasskeyDto> {
        check("rename")
        return V2Envelope(passkey(id, request.nickname))
    }

    override suspend fun delete(id: Long, reauthToken: String) {
        check("delete")
        reauthTokens += reauthToken
    }

    override suspend fun reauthOptions(body: JsonObject): V2Envelope<ChallengeDto> {
        check("reauthOptions")
        return V2Envelope(challenge("re-1"))
    }

    override suspend fun reauth(request: PasskeyReauthRequest): V2Envelope<ReauthTokenDto> {
        check("reauth")
        reauthRequest = request
        return V2Envelope(ReauthTokenDto("proof-pk", "2026-11-12T07:10:00Z"))
    }
}

/** A scriptable [PasskeyLoginApi]. */
internal class FakeLoginApi : PasskeyLoginApi {
    var available = true
    var error: Exception? = null
    var exchangeError: Exception? = null
    val exchanges = mutableListOf<List<Any>>()

    override suspend fun available(serverUrl: String) = available

    override suspend fun options(serverUrl: String): ChallengeDto {
        error?.let { throw it }
        return challenge("login-1")
    }

    override suspend fun exchange(
        serverUrl: String,
        challengeId: String,
        credential: kotlinx.serialization.json.JsonElement,
        deviceName: String,
    ): String {
        exchangeError?.let { throw it }
        exchanges += listOf(serverUrl, challengeId, credential, deviceName)
        return "pat-from-passkey"
    }
}
