package blue.anika.wolle.data.api

import blue.anika.wolle.data.api.v2.MetaV2Api
import blue.anika.wolle.data.api.v2.MetaV2Dto
import blue.anika.wolle.data.api.v2.MetaV2UserDto
import blue.anika.wolle.data.api.v2.V2Envelope
import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class ApiCapabilitiesTest {
    private class FakeMeta(var respond: () -> MetaV2Dto) : MetaV2Api {
        var calls = 0

        override suspend fun getMeta(): V2Envelope<MetaV2Dto> {
            calls++
            return V2Envelope(respond())
        }
    }

    private fun http(status: Int) =
        HttpException(
            Response.error<Any>(status, "{}".toResponseBody("application/json".toMediaType()))
        )

    private val admin =
        MetaV2Dto(user = MetaV2UserDto(id = 3, email = "me@example.org", isAdmin = true))

    private var key = "https://s\ntoken-a"

    private fun capabilities(meta: FakeMeta) = ApiCapabilities(meta) { key }

    @Test
    fun `200 with a user means v2 and carries the identity`() = runTest {
        val meta = FakeMeta { admin }
        val caps = capabilities(meta)

        assertEquals(ApiGeneration.V2, caps.generation())
        assertTrue(caps.useV2())
        assertEquals(CurrentUser(3, "me@example.org", true), caps.state.value.user)
    }

    @Test
    fun `the probe runs once per credentials`() = runTest {
        val meta = FakeMeta { admin }
        val caps = capabilities(meta)

        repeat(3) { caps.generation() }
        assertEquals(1, meta.calls)

        key = "https://s\ntoken-b"
        caps.generation()
        assertEquals(2, meta.calls)
    }

    @Test
    fun `404 means an old server and v1 is remembered`() = runTest {
        val meta = FakeMeta { throw http(404) }
        val caps = capabilities(meta)

        assertEquals(ApiGeneration.V1, caps.generation())
        assertEquals(ApiGeneration.V1, caps.generation())
        assertEquals(1, meta.calls)
        assertNull(caps.state.value.user)
    }

    @Test
    fun `200 without a user is not v2 for this token`() = runTest {
        val caps = capabilities(FakeMeta { MetaV2Dto() })
        assertEquals(ApiGeneration.V1, caps.generation())
    }

    @Test
    fun `401, 403, 5xx and network errors are thrown, not turned into v1, and re-probed`() =
        runTest {
            val failures =
                listOf<() -> MetaV2Dto>(
                    { throw http(401) },
                    { throw http(403) },
                    { throw http(503) },
                    { throw IOException("off") },
                )
            for (failure in failures) {
                val meta = FakeMeta(failure)
                val caps = capabilities(meta)
                val thrown = runCatching { caps.generation() }.exceptionOrNull()
                assertTrue("expected the probe failure, got $thrown", thrown != null)
                assertTrue(runCatching { caps.useV2() }.isFailure)
                assertEquals(2, meta.calls)

                meta.respond = { admin }
                assertEquals(ApiGeneration.V2, caps.generation())
            }
        }

    @Test
    fun `a failure after a good probe for other credentials does not leak the old answer`() =
        runTest {
            val meta = FakeMeta { admin }
            val caps = capabilities(meta)
            caps.generation()

            key = "https://other\ntoken"
            meta.respond = { throw http(401) }
            assertTrue(runCatching { caps.generation() }.isFailure)
            assertNull(caps.current.user)
        }

    @Test
    fun `the answer is kept per server url and token`() = runTest {
        val meta = FakeMeta { throw http(404) }
        val caps = capabilities(meta)
        assertEquals(ApiGeneration.V1, caps.generation())

        key = "https://other\ntoken-a"
        meta.respond = { admin }
        assertEquals(ApiGeneration.V2, caps.generation())
        assertEquals(2, meta.calls)
    }

    @Test
    fun `no credentials never probes and never picks v2`() = runTest {
        key = ""
        val meta = FakeMeta { admin }
        val caps = capabilities(meta)

        assertEquals(ApiGeneration.V1, caps.generation())
        assertEquals(0, meta.calls)
    }

    @Test
    fun `signing out drops the cached v2 identity`() = runTest {
        val caps = capabilities(FakeMeta { admin })
        caps.generation()
        key = ""
        assertEquals(ApiGeneration.V1, caps.generation())
        assertNull(caps.state.value.user)
        assertNull(caps.current.user)
    }

    @Test
    fun `invalidate forces a new probe`() = runTest {
        val meta = FakeMeta { admin }
        val caps = capabilities(meta)
        caps.generation()
        caps.invalidate()
        caps.generation()
        assertEquals(2, meta.calls)
    }
}
