package blue.anika.wolle.data.api.v2

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/** A cursor issued under one scope is refused under the other: the client restarts cleanly. */
class ScopedSyncTest {
    private fun http(status: Int, code: String) =
        HttpException(
            Response.error<Any>(
                status,
                """{"error":{"code":"$code","message":"x"}}"""
                    .toResponseBody("application/json".toMediaType()),
            )
        )

    private fun page(vararg ids: Int, time: String = "T2") =
        V2SyncPageDto(ids.toList(), emptyList(), time, false, false, null, scope = "all")

    @Test
    fun `scope_mismatch restarts without a cursor as a full resync`() = runTest {
        val calls = mutableListOf<Pair<String?, String?>>()
        val outcome =
            runScopedV2Sync<Int>(
                since = "MINE-CURSOR",
                idOf = { it },
                fetch = { since, cursor ->
                    calls += since to cursor
                    if (calls.size == 1) throw http(409, "scope_mismatch")
                    page(1, 7)
                },
                onPage = { _, _ -> },
            )

        assertEquals(listOf<Pair<String?, String?>>("MINE-CURSOR" to null, null to null), calls)
        assertTrue(outcome.fullResync)
        assertEquals(setOf(1, 7), outcome.seenIds)
        assertEquals("T2", outcome.serverTime)
    }

    @Test
    fun `other conflicts and errors are not swallowed`() = runTest {
        for (failure in listOf(http(409, "conflict"), http(500, "internal_error"))) {
            assertThrows(HttpException::class.java) {
                kotlinx.coroutines.runBlocking {
                    runScopedV2Sync<Int>(
                        since = null,
                        idOf = { it },
                        fetch = { _, _ -> throw failure },
                        onPage = { _, _ -> },
                    )
                }
            }
        }
    }

    @Test
    fun `a second scope_mismatch gives up instead of looping`() = runTest {
        var requests = 0
        assertThrows(HttpException::class.java) {
            kotlinx.coroutines.runBlocking {
                runScopedV2Sync<Int>(
                    since = "X",
                    idOf = { it },
                    fetch = { _, _ ->
                        requests++
                        throw http(409, "scope_mismatch")
                    },
                    onPage = { _, _ -> },
                )
            }
        }
        assertEquals(2, requests)
    }

    @Test
    fun `an ordinary sync is unchanged and is no full resync`() = runTest {
        val outcome =
            runScopedV2Sync<Int>(
                since = "T1",
                idOf = { it },
                fetch = { _, _ -> page(4) },
                onPage = { _, _ -> },
            )

        assertFalse(outcome.fullResync)
    }

    @Test
    fun `the scope the server applied decodes and defaults to absent`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val with =
            json.decodeFromString<V2SyncPageDto<Int>>(
                """{"updated":[],"server_time":"t","scope":"all"}"""
            )
        val without = json.decodeFromString<V2SyncPageDto<Int>>("""{"updated":[],"server_time":"t"}""")

        assertEquals("all", with.scope)
        assertEquals(null, without.scope)
    }

    @Test
    fun `an owner decodes from a project and maps through the v1 domain`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val dto =
            json.decodeFromString<ProjectV2Dto>(
                """{"id": 4, "name": "P", "is_ai_enhanced": false, "is_favorite": false,
                "created_at": "2026-11-01T12:00:00.000000Z",
                "updated_at": "2026-11-01T12:30:00.000000Z",
                "owner": {"id": 7, "email": "anika@example.org", "name": "Anika",
                          "avatar_url": "/api/v2/admin/users/7/avatar/gravatar"}}"""
            )

        val owner = dto.toV1().owner!!
        assertEquals(7L, owner.id)
        assertEquals("Anika", owner.name)
        assertEquals("/api/v2/admin/users/7/avatar/gravatar", owner.avatarUrl)
    }
}
