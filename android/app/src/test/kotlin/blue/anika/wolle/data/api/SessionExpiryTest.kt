package blue.anika.wolle.data.api

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionExpiryTest {
    private val server = MockWebServer()

    @After fun tearDown() = server.shutdown()

    private val sessionExpired = """{"error":{"code":"session_expired","message":"Sign in"}}"""

    @Test
    fun `a v2 session_expired is a rejected token`() {
        assertTrue(isRejectedTokenResponse("/api/v2/projects", sessionExpired))
    }

    @Test
    fun `a wrong password proof is not`() {
        assertFalse(
            isRejectedTokenResponse(
                "/api/v2/auth/token",
                """{"error":{"code":"invalid_credentials"}}""",
            )
        )
    }

    @Test
    fun `any 401 on a v1 data route is a rejected token, an unparsable one on v2 is not`() {
        assertTrue(
            isRejectedTokenResponse("/api/v1/projects", """{"detail":"Not authenticated"}""")
        )
        assertTrue(isRejectedTokenResponse("/api/v1/sync/projects", ""))
        assertFalse(isRejectedTokenResponse("/api/v2/projects", "not json"))
        assertFalse(isRejectedTokenResponse("/health", ""))
    }

    private fun call(client: OkHttpClient, path: String) {
        client
            .newCall(
                Request.Builder()
                    .url(server.url(path))
                    .header("Authorization", "Bearer pat-1")
                    .build()
            )
            .execute()
            .use { it.body.string() }
    }

    @Test
    fun `the interceptor reports a rejected token once per answer and an accepted one after`() {
        val expired = mutableListOf<String?>()
        val accepted = mutableListOf<String?>()
        val client =
            OkHttpClient.Builder()
                .addInterceptor(
                    SessionExpiryInterceptor(
                        onExpired = { expired += it },
                        onAccepted = { accepted += it },
                    )
                )
                .build()

        server.enqueue(MockResponse().setResponseCode(401).setBody(sessionExpired))
        call(client, "/api/v2/meta")
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"x"}"""))
        call(client, "/api/v1/projects")
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        call(client, "/api/v2/meta")

        assertEquals(listOf<String?>("pat-1", "pat-1"), expired)
        assertEquals(listOf<String?>("pat-1"), accepted)
    }
}
