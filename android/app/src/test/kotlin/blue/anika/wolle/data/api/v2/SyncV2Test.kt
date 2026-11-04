package blue.anika.wolle.data.api.v2

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Decoding of `src/api_v2/sync.rs` bodies, v2 -> v1 mapping, paging loop, full resync. */
class SyncV2Test {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private fun project(id: Int, updatedAt: String = "2026-11-01T12:30:00.000000Z") =
        """{"id": $id, "name": "P$id", "description": "**d**", "description_html": "<p>d</p>",
        "tags": ["winter gift"], "is_ai_enhanced": false, "is_favorite": false,
        "created_at": "2026-11-01T12:00:00.000000Z", "updated_at": "$updatedAt",
        "yarn_ids": [], "steps": [], "images": [], "attachments": []}"""

    private fun page(
        updated: List<Int> = emptyList(),
        deleted: List<Int> = emptyList(),
        time: String = "2026-11-03T10:00:00.000000Z",
        full: Boolean = false,
        hasMore: Boolean = false,
        next: String? = null,
    ): V2SyncPageDto<Int> = V2SyncPageDto(updated, deleted, time, full, hasMore, next)

    @Test
    fun `decodes the wrapped project sync body`() {
        val body =
            """{"data": {"updated": [${project(4)}], "deleted_ids": [9, 10],
            "server_time": "2026-11-03T10:00:00.123456Z", "full_resync_required": false,
            "has_more": true, "next_cursor": "abc_-"}}"""
        val data = json.decodeFromString<V2Envelope<V2SyncPageDto<ProjectV2Dto>>>(body).data

        assertEquals(listOf(4), data.updated.map { it.id })
        assertEquals(listOf(9, 10), data.deletedIds)
        assertEquals("2026-11-03T10:00:00.123456Z", data.serverTime)
        assertFalse(data.fullResyncRequired)
        assertTrue(data.hasMore)
        assertEquals("abc_-", data.nextCursor)
    }

    @Test
    fun `a null cursor and missing optional keys decode to defaults`() {
        val body =
            """{"data": {"updated": [], "server_time": "2026-11-03T10:00:00.000000Z",
            "next_cursor": null}}"""
        val data = json.decodeFromString<V2Envelope<V2SyncPageDto<YarnV2Dto>>>(body).data

        assertTrue(data.updated.isEmpty())
        assertTrue(data.deletedIds.isEmpty())
        assertFalse(data.hasMore)
        assertNull(data.nextCursor)
    }

    @Test
    fun `updated entries map to the v1 domain like the detail dto`() {
        val data =
            json
                .decodeFromString<V2Envelope<V2SyncPageDto<ProjectV2Dto>>>(
                    """{"data": {"updated": [${project(4)}], "deleted_ids": [],
                    "server_time": "t"}}"""
                )
                .data
        val mapped = data.updated.single().toV1()

        assertEquals(4, mapped.id)
        assertEquals(listOf("winter gift"), mapped.tags)
        assertEquals("**d**", mapped.description)
    }

    @Test
    fun `a single page is one request from the stored cursor`() = runTest {
        val calls = mutableListOf<Pair<String?, String?>>()
        val applied = mutableListOf<Pair<List<Int>, List<Int>>>()
        val outcome =
            runV2Sync<Int>(
                since = "T0",
                idOf = { it },
                fetch = { since, cursor ->
                    calls += since to cursor
                    page(updated = listOf(1, 2), deleted = listOf(7), time = "T1")
                },
                onPage = { u, d -> applied += u to d },
            )

        assertEquals(listOf<Pair<String?, String?>>("T0" to null), calls)
        assertEquals(listOf(listOf(1, 2) to listOf(7)), applied)
        assertEquals("T1", outcome.serverTime)
        assertFalse(outcome.fullResync)
    }

    @Test
    fun `follows next_cursor without since and keeps the first server_time`() = runTest {
        val calls = mutableListOf<Pair<String?, String?>>()
        val applied = mutableListOf<List<Int>>()
        val pages =
            ArrayDeque(
                listOf(
                    page(updated = listOf(1), time = "T1", hasMore = true, next = "c1"),
                    page(
                        updated = listOf(2),
                        deleted = listOf(5),
                        time = "T1",
                        hasMore = true,
                        next = "c2",
                    ),
                    page(updated = listOf(3), time = "T1"),
                )
            )
        val outcome =
            runV2Sync<Int>(
                since = "T0",
                idOf = { it },
                fetch = { since, cursor ->
                    calls += since to cursor
                    pages.removeFirst()
                },
                onPage = { u, _ -> applied += u },
            )

        assertEquals(
            listOf<Pair<String?, String?>>("T0" to null, null to "c1", null to "c2"),
            calls,
        )
        assertEquals(listOf(listOf(1), listOf(2), listOf(3)), applied)
        assertEquals("T1", outcome.serverTime)
    }

    @Test
    fun `an interrupted run never yields a cursor`() = runTest {
        var requests = 0
        val failure = runCatching {
            runV2Sync<Int>(
                since = null,
                idOf = { it },
                fetch = { _, _ ->
                    if (++requests == 1) page(updated = listOf(1), hasMore = true, next = "c1")
                    else error("offline")
                },
                onPage = { _, _ -> },
            )
        }
        assertTrue(failure.isFailure)
    }

    @Test
    fun `full_resync_required refetches from scratch and reports every id`() = runTest {
        val calls = mutableListOf<Pair<String?, String?>>()
        val applied = mutableListOf<List<Int>>()
        val pages =
            ArrayDeque(
                listOf(
                    page(full = true, time = "ignored"),
                    page(updated = listOf(1, 2), time = "T2", hasMore = true, next = "c1"),
                    page(updated = listOf(3), time = "T2"),
                )
            )
        val outcome =
            runV2Sync<Int>(
                since = "OLD",
                idOf = { it },
                fetch = { since, cursor ->
                    calls += since to cursor
                    pages.removeFirst()
                },
                onPage = { u, _ -> applied += u },
            )

        assertEquals(
            listOf<Pair<String?, String?>>("OLD" to null, null to null, null to "c1"),
            calls,
        )
        assertEquals(listOf(listOf(1, 2), listOf(3)), applied)
        assertTrue(outcome.fullResync)
        assertEquals(setOf(1, 2, 3), outcome.seenIds)
        assertEquals("T2", outcome.serverTime)
    }

    @Test
    fun `a server that keeps full_resync_required set cannot loop the client`() = runTest {
        var requests = 0
        val outcome =
            runV2Sync<Int>(
                since = "OLD",
                idOf = { it },
                fetch = { _, _ ->
                    requests++
                    page(updated = listOf(1), full = true, time = "T")
                },
                onPage = { _, _ -> },
            )
        assertEquals(2, requests)
        assertEquals(setOf(1), outcome.seenIds)
    }

    @Test
    fun `has_more without a fresh cursor is a protocol error`() = runTest {
        for (next in listOf(null, "same")) {
            var first = true
            val failure = runCatching {
                runV2Sync<Int>(
                    since = null,
                    idOf = { it },
                    fetch = { _, cursor ->
                        if (first) {
                            first = false
                            page(hasMore = true, next = "same")
                        } else page(hasMore = true, next = next ?: cursor)
                    },
                    onPage = { _, _ -> },
                )
            }
            assertThrows(V2SyncProtocolException::class.java) { failure.getOrThrow() }
        }
    }

    @Test
    fun `stale ids after a full resync skip placeholders, seen and pending rows`() {
        val stale =
            staleAfterFullResync(
                localIds = listOf(-3, 1, 2, 3, 4),
                seen = setOf(1),
                pendingLocalIds = setOf(3),
            )
        assertEquals(listOf(2, 4), stale)
    }
}
