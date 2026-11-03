package blue.anika.wolle.data.onboarding

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingV2Test {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }
    private val server = MockWebServer()
    private val seen = mutableListOf<String>()
    private val bearers = mutableListOf<String?>()
    private val client = OkHttpClient()

    @After fun tearDown() = server.shutdown()

    private fun serve(handler: (RecordedRequest) -> MockResponse) {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    seen += "${request.method} ${request.path}"
                    bearers += request.getHeader("Authorization")
                    return handler(request)
                }
            }
        server.start()
    }

    private fun body(code: Int, text: String) =
        MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(text)

    private val anonymousMeta =
        """{"data":{"version":"1","build_id":"x","is_admin":false,"user":null}}"""
    private val userMeta =
        """{"data":{"version":"1","is_admin":false,"user":{"id":3,"email":"a@b.c","is_admin":false}}}"""

    private fun url() = server.url("/").toString()

    private fun errorOf(result: Result<*>): OnboardingError =
        (result.exceptionOrNull() as OnboardingValidationException).error

    private val validator
        get() = OnboardingValidator(client, json)

    private val minter
        get() = PasswordTokenMinter(client, json)

    @Test
    fun `a v2 server is identified by the anonymous meta and the token is checked on v2`() =
        runTest {
            serve {
                if (it.getHeader("Authorization") == null) body(200, anonymousMeta)
                else body(200, userMeta)
            }

            assertTrue(validator.validate(url(), " tok ").isSuccess)
            assertEquals(listOf("GET /api/v2/meta", "GET /api/v2/meta"), seen)
            assertEquals(listOf(null, "Bearer tok"), bearers)
        }

    @Test
    fun `a rejected token on a v2 server is Unauthorized`() = runTest {
        serve {
            if (it.getHeader("Authorization") == null) body(200, anonymousMeta)
            else body(401, """{"error":{"code":"session_expired","message":"x"}}""")
        }
        assertEquals(OnboardingError.Unauthorized, errorOf(validator.validate(url(), "bad")))
    }

    @Test
    fun `a server without v2 falls back to v1 meta and categories`() = runTest {
        serve {
            when (it.path) {
                "/api/v2/meta" -> body(404, "{}")
                "/api/v1/meta" -> body(200, """{"version":"1"}""")
                else -> body(200, "[]")
            }
        }
        assertTrue(validator.validate(url(), "tok").isSuccess)
        assertEquals(
            listOf("GET /api/v2/meta", "GET /api/v1/meta", "GET /api/v1/categories"),
            seen,
        )
    }

    @Test
    fun `405 also selects v1 but a 5xx never falls back`() = runTest {
        serve { body(503, "{}") }
        assertEquals(OnboardingError.ServerError(503), errorOf(validator.validate(url(), "tok")))
        assertEquals(listOf("GET /api/v2/meta"), seen)
    }

    @Test
    fun `an html 200 is not a v2 server`() = runTest {
        serve {
            when (it.path) {
                "/api/v2/meta" -> body(200, "<html></html>")
                "/api/v1/meta" -> body(404, "{}")
                else -> body(404, "{}")
            }
        }
        assertEquals(
            OnboardingError.NotAStricknaniServer(404),
            errorOf(validator.validate(url(), "tok")),
        )
    }

    @Test
    fun `password login on v2 posts to auth token and unwraps data token`() = runTest {
        var posted = ""
        serve {
            if (it.path == "/api/v2/meta") body(200, anonymousMeta)
            else {
                posted = it.body.readUtf8()
                body(200, """{"data":{"token":"pat-123"}}""")
            }
        }
        val result = minter.mintToken(url(), " me@x.org ", "pw", "Android")

        assertEquals("pat-123", result.getOrThrow())
        assertEquals(listOf("GET /api/v2/meta", "POST /api/v2/auth/token"), seen)
        assertTrue(posted.contains("\"email\":\"me@x.org\""))
        assertTrue(posted.contains("\"token_name\":\"Android\""))
    }

    private suspend fun mintWith(status: Int, text: String): Result<String> {
        serve { if (it.path == "/api/v2/meta") body(200, anonymousMeta) else body(status, text) }
        return minter.mintToken(url(), "me@x.org", "pw", "Android")
    }

    @Test
    fun `v2 401 and 422 are invalid credentials`() = runTest {
        assertEquals(
            OnboardingError.InvalidCredentials,
            errorOf(mintWith(401, """{"error":{"code":"invalid_credentials","message":"x"}}""")),
        )
    }

    @Test
    fun `v2 422 validation error is invalid credentials`() = runTest {
        assertEquals(
            OnboardingError.InvalidCredentials,
            errorOf(
                mintWith(422, """{"error":{"code":"validation_error","fields":{"email":"x"}}}""")
            ),
        )
    }

    @Test
    fun `v2 429 is RateLimited and not a server error`() = runTest {
        assertEquals(
            OnboardingError.RateLimited,
            errorOf(mintWith(429, """{"error":{"code":"rate_limited","message":"x"}}""")),
        )
    }

    @Test
    fun `v2 500 stays a server error`() = runTest {
        assertEquals(OnboardingError.ServerError(500), errorOf(mintWith(500, "{}")))
    }

    @Test
    fun `password login falls back to v1 on a server without v2`() = runTest {
        serve {
            if (it.path == "/api/v2/meta") body(404, "{}") else body(200, """{"token":"old-pat"}""")
        }
        assertEquals("old-pat", minter.mintToken(url(), "me@x.org", "pw", "Android").getOrThrow())
        assertEquals(listOf("GET /api/v2/meta", "POST /api/v1/auth/token"), seen)
    }

    @Test
    fun `an unreachable server is reported as such`() = runTest {
        server.start()
        val address = url()
        server.shutdown()
        assertEquals(OnboardingError.Unreachable, errorOf(minter.mintToken(address, "a", "b", "c")))
    }
}
