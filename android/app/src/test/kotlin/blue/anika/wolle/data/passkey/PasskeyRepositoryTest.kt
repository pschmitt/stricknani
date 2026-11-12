package blue.anika.wolle.data.passkey

import blue.anika.wolle.data.oidc.ReauthAction
import blue.anika.wolle.data.oidc.ReauthProof
import blue.anika.wolle.data.oidc.ReauthTarget
import blue.anika.wolle.data.oidc.retrofitHttp
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PasskeyRepositoryTest {
    private val gateway = FakeGateway()
    private val api = FakePasskeyApi()
    private val login = FakeLoginApi()
    private val repository =
        PasskeyRepository(api, login, gateway, Json { ignoreUnknownKeys = true })
    private val manage = ReauthProof(ReauthAction.MANAGE_PASSKEYS, ReauthTarget.None, "proof-1")

    @Test
    fun `sign in gives the system sheet the public key options and trades the assertion for a token`() =
        runTest {
            val token = repository.signIn(NoWindow, "https://wolle.example.org", "Pixel 9")

            assertEquals("pat-from-passkey", token.getOrNull())
            val sent = Json.parseToJsonElement(gateway.getRequests.single()).jsonObject
            assertEquals("wolle.example.org", sent["rpId"]!!.jsonPrimitive.content)
            assertFalse("the options wrapper is not forwarded", sent.containsKey("publicKey"))
            val exchange = login.exchanges.single()
            assertEquals("https://wolle.example.org", exchange[0])
            assertEquals("login-1", exchange[1])
            assertEquals("Pixel 9", exchange[3])
            assertEquals(
                "sig",
                (exchange[2] as kotlinx.serialization.json.JsonObject)["id"]!!
                    .jsonPrimitive
                    .content,
            )
        }

    @Test
    fun `a dismissed sheet is a cancel and nothing is exchanged`() = runTest {
        gateway.got = PasskeyOutcome.Failed(PasskeyFailure.Cancelled)

        val result = repository.signIn(NoWindow, "https://s", "Pixel")

        assertEquals(PasskeyFailure.Cancelled, result.passkeyFailure())
        assertTrue(login.exchanges.isEmpty())
    }

    @Test
    fun `a phone without a passkey for the server says so`() = runTest {
        gateway.got = PasskeyOutcome.Failed(PasskeyFailure.NoCredential)
        assertEquals(
            PasskeyFailure.NoCredential,
            repository.signIn(NoWindow, "https://s", "Pixel").passkeyFailure(),
        )
    }

    @Test
    fun `the server refusing the assertion is not recognized`() = runTest {
        login.exchangeError = PasskeyHttpException(401, "invalid_passkey", null)
        assertEquals(
            PasskeyFailure.NotRecognized,
            repository.signIn(NoWindow, "https://s", "Pixel").passkeyFailure(),
        )
    }

    @Test
    fun `offline sign in is offline`() = runTest {
        login.error = IOException("down")
        assertEquals(
            PasskeyFailure.Offline,
            repository.signIn(NoWindow, "https://s", "Pixel").passkeyFailure(),
        )
        assertTrue("the sheet never opened", gateway.getRequests.isEmpty())
    }

    @Test
    fun `registering sends the proof with both calls and the sheet's response as it is`() =
        runTest {
            val created = repository.register(NoWindow, "My phone", manage)

            assertEquals("My phone", created.getOrNull()?.nickname)
            assertEquals(listOf("proof-1", "proof-1"), api.reauthTokens)
            val request = api.registered!!
            assertEquals("reg-1", request.challengeId)
            assertEquals("new", request.credential.jsonObject["id"]!!.jsonPrimitive.content)
            val sheet = Json.parseToJsonElement(gateway.createRequests.single()).jsonObject
            assertEquals("AAEC", sheet["challenge"]!!.jsonPrimitive.content)
        }

    @Test
    fun `a proof for another action is not sent`() = runTest {
        val other = ReauthProof(ReauthAction.DELETE_ACCOUNT, ReauthTarget.None, "p")
        assertEquals(
            PasskeyFailure.ReauthRequired,
            repository.register(NoWindow, "x", other).passkeyFailure(),
        )
        assertEquals(
            PasskeyFailure.ReauthRequired,
            repository.delete(1, other).passkeyFailure(),
        )
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `a device that already holds a passkey does not register again`() = runTest {
        gateway.created = PasskeyOutcome.Failed(PasskeyFailure.AlreadyRegistered)
        assertEquals(
            PasskeyFailure.AlreadyRegistered,
            repository.register(NoWindow, "x", manage).passkeyFailure(),
        )
        assertFalse(api.calls.contains("registrationVerify"))
    }

    @Test
    fun `a step up that has expired is reported`() = runTest {
        api.error = retrofitHttp(403, "reauth_required")
        assertEquals(
            PasskeyFailure.ReauthRequired,
            repository.register(NoWindow, "x", manage).passkeyFailure(),
        )
    }

    @Test
    fun `listing returns the passkeys and the limit`() = runTest {
        val list = repository.list().getOrNull()!!
        assertEquals(1, list.passkeys.size)
        assertEquals(10, list.limit)
    }

    @Test
    fun `a server without passkeys is unavailable`() = runTest {
        api.error = retrofitHttp(404, "passkeys_unavailable")
        assertEquals(PasskeyFailure.Unavailable, repository.list().passkeyFailure())
    }

    @Test
    fun `renaming and deleting reach the server`() = runTest {
        assertEquals("Work", repository.rename(1, "Work").getOrNull()?.nickname)
        assertTrue(repository.delete(1, manage).isSuccess)
        assertEquals(listOf("rename", "delete"), api.calls)
        assertEquals(listOf("proof-1"), api.reauthTokens)
    }

    @Test
    fun `confirming with a passkey yields a proof bound to the action`() = runTest {
        val proof =
            repository
                .reauth(NoWindow, ReauthAction.MANAGE_PASSKEYS, ReauthTarget.None)
                .getOrNull()!!

        assertEquals(ReauthAction.MANAGE_PASSKEYS, proof.action)
        assertEquals("proof-pk", proof.token)
        val request = api.reauthRequest!!
        assertEquals("re-1", request.challengeId)
        assertEquals("manage_passkeys", request.action)
        assertNull(request.target)
    }

    @Test
    fun `a cancelled confirmation sends nothing`() = runTest {
        gateway.got = PasskeyOutcome.Failed(PasskeyFailure.Cancelled)
        val result = repository.reauth(NoWindow, ReauthAction.MANAGE_PASSKEYS, ReauthTarget.None)
        assertEquals(PasskeyFailure.Cancelled, result.passkeyFailure())
        assertNull(api.reauthRequest)
    }
}

/** The unauthenticated client against a real socket: paths, bodies and error codes. */
class HttpPasskeyLoginApiTest {
    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var api: HttpPasskeyLoginApi
    private lateinit var base: String

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        base = server.url("/").toString().trimEnd('/')
        api = HttpPasskeyLoginApi(OkHttpClient(), json)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `availability is read from the public metadata`() = runTest {
        server.enqueue(
            MockResponse()
                .setBody("""{"data":{"auth":{"password":true,"oidc":[],"passkeys":true}}}""")
        )
        assertTrue(api.available(base))
        assertEquals("/api/v2/meta", server.takeRequest().path)

        server.enqueue(MockResponse().setBody("""{"data":{"auth":{"password":true,"oidc":[]}}}"""))
        assertFalse("an older server has no passkeys", api.available(base))
        server.enqueue(MockResponse().setResponseCode(500))
        assertFalse(api.available(base))
        assertFalse(api.available("not a url"))
    }

    @Test
    fun `options and exchange are unauthenticated and use the token routes`() = runTest {
        server.enqueue(
            MockResponse()
                .setBody(
                    """{"data":{"challenge_id":"abc","options":{"publicKey":{"challenge":"AAEC"}}}}"""
                )
        )
        val challenge = api.options(base)
        assertEquals("abc", challenge.challengeId)
        val first = server.takeRequest()
        assertEquals("POST", first.method)
        assertEquals("/api/v2/auth/passkey/token/options", first.path)
        assertNull(first.getHeader("Authorization"))

        server.enqueue(MockResponse().setBody("""{"data":{"token":"sna_abc"}}"""))
        val token =
            api.exchange(
                base,
                "abc",
                json.parseToJsonElement("""{"id":"x","type":"public-key"}"""),
                "Pixel 9",
            )
        assertEquals("sna_abc", token)
        val second = server.takeRequest()
        assertEquals("/api/v2/auth/passkey/token", second.path)
        val body = json.parseToJsonElement(second.body.readUtf8()).jsonObject
        assertEquals("abc", body["challenge_id"]!!.jsonPrimitive.content)
        assertEquals("Pixel 9", body["device_name"]!!.jsonPrimitive.content)
        assertEquals("x", body["credential"]!!.jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an error answer carries its code and Retry-After`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "300")
                .setBody("""{"error":{"code":"rate_limited","message":"x"}}""")
        )
        val failure = runCatching { api.options(base) }.exceptionOrNull()!!
        assertEquals(PasskeyFailure.RateLimited(300), failure.toPasskeyFailure())

        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"code":"invalid_passkey","message":"x"}}""")
        )
        val rejected =
            runCatching { api.exchange(base, "a", json.parseToJsonElement("{}"), "p") }
                .exceptionOrNull()!!
        assertEquals(PasskeyFailure.NotRecognized, rejected.toPasskeyFailure())
    }
}
