package blue.anika.wolle.data.oidc

import blue.anika.wolle.data.api.SessionExpiryInterceptor
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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class OidcLoginApiTest {
    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var api: HttpOidcLoginApi
    private lateinit var base: String

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        base = server.url("/").toString().trimEnd('/')
        api = HttpOidcLoginApi(OkHttpClient(), json)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `providers are read from data auth oidc`() = runTest {
        server.enqueue(
            MockResponse()
                .setBody(
                    """{"data":{"version":"1","auth":{"password":true,"oidc":[{"id":"authelia","name":"Authelia"}]},"user":null}}"""
                )
        )
        assertEquals(listOf(OidcProvider("authelia", "Authelia")), api.providers(base))
        val request = server.takeRequest()
        assertEquals("/api/v2/meta", request.path)
        assertNull(request.getHeader("Authorization"))
    }

    @Test
    fun `no providers today means nothing is offered`() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":{"version":"1","user":null}}"""))
        assertTrue(api.providers(base).isEmpty())
        server.enqueue(MockResponse().setBody("""{"data":{"auth":{"password":true,"oidc":[]}}}"""))
        assertTrue(api.providers(base).isEmpty())
    }

    @Test
    fun `providers stay empty on errors and bad bodies`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        assertTrue(api.providers(base).isEmpty())
        server.enqueue(MockResponse().setBody("<html>nope</html>"))
        assertTrue(api.providers(base).isEmpty())
        server.shutdown()
        assertTrue(api.providers(base).isEmpty())
        assertTrue(api.providers("not a url").isEmpty())
    }

    @Test
    fun `start posts the native fields without credentials`() = runTest {
        server.enqueue(
            MockResponse()
                .setBody(
                    """{"data":{"authorization_url":"https://auth.example/a?s=1","expires_at":"2026-11-03T12:00:00Z"}}"""
                )
        )
        val response =
            api.start(
                base,
                OidcStartRequest(
                    provider = "authelia",
                    client = "android",
                    redirectUri = OIDC_REDIRECT_URI,
                    appState = "state-abc",
                    codeChallenge = "challenge",
                    codeChallengeMethod = "S256",
                ),
            )
        assertEquals("https://auth.example/a?s=1", response.authorizationUrl)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v2/auth/oidc/start", request.path)
        assertNull(request.getHeader("Authorization"))
        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("authelia", body["provider"]!!.jsonPrimitive.content)
        assertEquals("android", body["client"]!!.jsonPrimitive.content)
        assertEquals("stricknani://oidc-callback", body["redirect_uri"]!!.jsonPrimitive.content)
        assertEquals("state-abc", body["app_state"]!!.jsonPrimitive.content)
        assertEquals("challenge", body["code_challenge"]!!.jsonPrimitive.content)
        assertEquals("S256", body["code_challenge_method"]!!.jsonPrimitive.content)
    }

    @Test
    fun `exchange returns the token its expiry and origin`() = runTest {
        server.enqueue(
            MockResponse()
                .setBody(
                    """{"data":{"token":"pat-1","expires_at":"2026-11-10T12:00:00Z","auth_origin":"oidc"}}"""
                )
        )
        val result =
            api.exchange(
                base,
                OidcLoginExchangeRequest(
                    "code-1",
                    "verifier",
                    OIDC_REDIRECT_URI,
                    "Android · Pixel 9",
                    "Pixel 9",
                ),
            )
        assertEquals("pat-1", result.token)
        assertEquals("2026-11-10T12:00:00Z", result.expiresAt)
        assertEquals("oidc", result.authOrigin)
        val request = server.takeRequest()
        assertEquals("/api/v2/auth/oidc/exchange", request.path)
        assertFalse("tokens never go in URLs", request.path!!.contains("code"))
        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("code-1", body["code"]!!.jsonPrimitive.content)
        assertEquals("verifier", body["code_verifier"]!!.jsonPrimitive.content)
        assertEquals("Android · Pixel 9", body["token_name"]!!.jsonPrimitive.content)
        assertEquals("Pixel 9", body["device_name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `exchange leaves device_name out when there is none`() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":{"token":"pat-1"}}"""))
        api.exchange(base, OidcLoginExchangeRequest("c", "v", OIDC_REDIRECT_URI))
        val body = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertFalse(body.containsKey("device_name"))
    }

    @Test
    fun `errors carry status code and Retry-After and no body text`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "9")
                .setBody("""{"error":{"code":"rate_limited","message":"secret detail"}}""")
        )
        try {
            api.exchange(base, OidcLoginExchangeRequest("c", "v", OIDC_REDIRECT_URI))
            fail("expected a failure")
        } catch (e: OidcHttpException) {
            assertEquals(429, e.status)
            assertEquals("rate_limited", e.code)
            assertEquals(9L, e.retryAfterSeconds)
            assertEquals(OidcFailure.RateLimited(9), e.toOidcFailure())
            assertFalse(e.message!!.contains("secret"))
        }

        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody("""{"error":{"code":"oidc_identity_unlinked"}}""")
        )
        try {
            api.exchange(base, OidcLoginExchangeRequest("c", "v", OIDC_REDIRECT_URI))
            fail("expected a failure")
        } catch (e: OidcHttpException) {
            assertEquals(OidcFailure.Unlinked, e.toOidcFailure())
        }
    }

    @Test
    fun `an unreachable server throws an IO failure`() = runTest {
        server.shutdown()
        try {
            api.start(
                base,
                OidcStartRequest("authelia", "android", OIDC_REDIRECT_URI, "s", "c", "S256"),
            )
            fail("expected a failure")
        } catch (e: IOException) {
            assertEquals(OidcFailure.Offline, e.toOidcFailure())
        }
    }

    @Test
    fun `current user email reads meta with the given bearer`() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"data":{"user":{"id":1,"email":"me@example.org"}}}""")
        )
        assertEquals("me@example.org", api.currentUserEmail(base, "pat-1"))
        assertEquals("Bearer pat-1", server.takeRequest().getHeader("Authorization"))
        server.enqueue(MockResponse().setResponseCode(401))
        assertNull(api.currentUserEmail(base, "pat-1"))
    }

    @Test
    fun `session expiry interceptor flags only a 401 session_expired with the used token`() {
        val seen = mutableListOf<String?>()
        val client =
            OkHttpClient.Builder()
                .addInterceptor(SessionExpiryInterceptor(onExpired = { seen += it }))
                .build()
        fun call(response: MockResponse) {
            server.enqueue(response)
            client
                .newCall(
                    okhttp3.Request.Builder()
                        .url(server.url("/api/v2/projects"))
                        .header("Authorization", "Bearer pat-9")
                        .build()
                )
                .execute()
                .use { it.body.string() }
        }
        call(
            MockResponse().setResponseCode(401).setBody("""{"error":{"code":"session_expired"}}""")
        )
        assertEquals(listOf<String?>("pat-9"), seen)
        call(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"code":"invalid_credentials"}}""")
        )
        call(
            MockResponse().setResponseCode(403).setBody("""{"error":{"code":"session_expired"}}""")
        )
        call(MockResponse().setResponseCode(200).setBody("{}"))
        call(MockResponse().setResponseCode(401).setBody("not json"))
        assertEquals(1, seen.size)
    }
}
