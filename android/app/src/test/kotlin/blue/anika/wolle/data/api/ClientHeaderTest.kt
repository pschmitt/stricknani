package blue.anika.wolle.data.api

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class ClientHeaderTest {
    private val server = MockWebServer()

    @After fun tearDown() = server.shutdown()

    @Test
    fun `the header names the version, the build code and the flavour`() {
        assertEquals(
            "android/1.7.1 (code 123; debug)",
            clientHeaderValue("1.7.1", 123, debug = true),
        )
        assertEquals(
            "android/1.7.1-rc.1+3 (code 9; release)",
            clientHeaderValue("1.7.1-rc.1+3", 9, debug = false),
        )
    }

    @Test
    fun `a version the server would reject is replaced`() {
        assertEquals("android/unknown (code 1; debug)", clientHeaderValue("1.7 1", 1, true))
        assertEquals("android/unknown (code 1; debug)", clientHeaderValue("", 1, true))
        assertEquals(
            "android/unknown (code 1; release)",
            clientHeaderValue("x".repeat(40), 1, false),
        )
    }

    @Test
    fun `the interceptor sends the header on every request`() {
        server.enqueue(MockResponse().setBody("{}"))
        val client =
            OkHttpClient.Builder()
                .addInterceptor(ClientHeaderInterceptor("android/1.7.1 (code 123; debug)"))
                .build()
        client
            .newCall(Request.Builder().url(server.url("/api/v2/projects")).build())
            .execute()
            .use {
                it.body.string()
            }
        assertEquals(
            "android/1.7.1 (code 123; debug)",
            server.takeRequest().getHeader(CLIENT_HEADER),
        )
    }
}
