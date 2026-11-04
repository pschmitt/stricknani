package blue.anika.wolle.data.onboarding

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegistrationClientTest {
    private val server = MockWebServer()
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }
    private val client
        get() = RegistrationClient(OkHttpClient(), json)

    @After fun tearDown() = server.shutdown()

    private fun url() = server.url("/").toString()

    private fun reply(code: Int, body: String = "{}") {
        server.enqueue(
            MockResponse()
                .setResponseCode(code)
                .setHeader("Content-Type", "application/json")
                .setBody(body)
        )
    }

    private fun errorOf(result: Result<*>): RegistrationError =
        (result.exceptionOrNull() as RegistrationException).error

    @Test
    fun isOpenReadsTheRegistrationFlagFromMeta() = runTest {
        reply(
            200,
            """{"data":{"version":"1","is_admin":false,"registration_enabled":true,"user":null}}""",
        )
        reply(
            200,
            """{"data":{"version":"1","is_admin":false,"registration_enabled":false,"user":null}}""",
        )
        assertEquals(true, client.isOpen(url()))
        assertEquals(false, client.isOpen(url()))
        assertEquals("/api/v2/meta", server.takeRequest().path)
    }

    @Test
    fun isOpenIsNullWhenTheServerDoesNotSay() = runTest {
        reply(200, """{"data":{"version":"1","is_admin":false,"user":null}}""")
        reply(500)
        reply(200, "not json")
        assertNull(client.isOpen(url()))
        assertNull(client.isOpen(url()))
        assertNull(client.isOpen(url()))
        assertNull(client.isOpen("not a url"))
    }

    @Test
    fun registerPostsCredentialsAndReturnsTheToken() = runTest {
        reply(201, """{"data":{"token":"tok-123"}}""")
        val result = client.register(url(), " New@Example.test ", "long-enough-password")
        assertEquals("tok-123", result.getOrNull())
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v2/auth/register", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"email\":\"New@Example.test\""))
        assertTrue(!body.contains("device_name"))
    }

    @Test
    fun registerSendsTheDeviceNameWhenGiven() = runTest {
        reply(201, """{"data":{"token":"t"}}""")
        client.register(url(), "a@b.test", "long-enough-password", deviceName = "Pixel 9")
        assertTrue(server.takeRequest().body.readUtf8().contains("\"device_name\":\"Pixel 9\""))
    }

    @Test
    fun registerMapsServerAnswersToErrors() = runTest {
        reply(404, """{"error":{"code":"signup_disabled","message":"x"}}""")
        reply(409, """{"error":{"code":"registration_failed","message":"x"}}""")
        reply(422, """{"error":{"code":"validation_error","message":"x"}}""")
        reply(429)
        reply(503)
        val results = List(5) { client.register(url(), "a@b.test", "long-enough-password") }
        assertEquals(RegistrationError.Closed, errorOf(results[0]))
        assertEquals(RegistrationError.NotCreated, errorOf(results[1]))
        assertEquals(RegistrationError.Invalid, errorOf(results[2]))
        assertEquals(RegistrationError.RateLimited, errorOf(results[3]))
        assertEquals(RegistrationError.ServerError(503), errorOf(results[4]))
    }

    @Test
    fun registerReportsAnUnreachableServer() = runTest {
        val dead = server.url("/").toString()
        server.shutdown()
        assertEquals(
            RegistrationError.Unreachable,
            errorOf(client.register(dead, "a@b.test", "long-enough-password")),
        )
    }
}
