package blue.anika.wolle.data.repository

import blue.anika.wolle.data.api.dto.AccessDto
import blue.anika.wolle.data.api.dto.OwnerDto
import blue.anika.wolle.data.api.dto.ProjectDto
import blue.anika.wolle.data.api.v2.V2SyncPageDto
import blue.anika.wolle.data.api.v2.runV2Sync
import blue.anika.wolle.data.api.v2.staleAfterFullResync
import blue.anika.wolle.data.db.entity.ProjectEntity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sync of shared items on the device (`docs/sharing-api.md`, "Sync"): a fake server pages deltas
 * through the real cursor loop ([runV2Sync]) into an in-memory cache through [applyItemDelta], so
 * delta cursors, tombstones, retries and queued writes are exercised together.
 */
class SharedSyncTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val me = 1L

    private fun project(
        id: Int,
        owner: Long = me,
        role: String = "owner",
        derived: Boolean = false,
        updated: Long = 0,
    ): ProjectDto =
        ProjectDto(
            id = id,
            name = "P$id",
            isAiEnhanced = false,
            isFavorite = false,
            createdAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-01T00:00:0${updated}Z",
            owner = OwnerDto(id = owner, name = "Owner$owner"),
            access =
                AccessDto(
                    role = role,
                    derived = derived,
                    canEdit = role != "viewer",
                    canManage = role == "owner",
                ),
        )

    /** The device: a Room stand-in plus the evicted media paths and queued-write ids. */
    private class Device(var pending: Set<Int> = emptySet()) {
        val rows = linkedMapOf<Int, ProjectEntity>()
        val evicted = mutableListOf<String>()
        val details = mutableMapOf<Int, ProjectDto>()
    }

    private suspend fun Device.apply(
        updated: List<ProjectDto>,
        deleted: List<Int>,
        json: Json,
    ): Boolean =
        applyItemDelta(
            updated = updated,
            deletedIds = deleted,
            idOf = { it.id },
            toEntity = { dto ->
                details[dto.id] = dto
                dto.toEntity(json)
            },
            pendingIds = { ids -> ids.filter { it in pending }.toSet() },
            upsert = { list -> list.forEach { rows[it.id] = it } },
            load = { rows[it] },
            delete = { ids -> ids.forEach { rows.remove(it) } },
            evictMedia = { row -> evicted += "evict-${row.id}" },
        )

    @Test
    fun `a shared item older than the cursor still arrives, with role and owner`() = runTest {
        val device = Device()
        device.rows[1] = project(1).toEntity(json)

        // The server puts the newly shared item into the delta although it is old.
        device.apply(listOf(project(2, owner = 7, role = "viewer", updated = 1)), emptyList(), json)

        val shared = device.rows.getValue(2)
        assertEquals(7L, shared.ownerId)
        assertEquals("viewer", shared.accessRole)
        assertEquals(false, shared.canEdit)
        assertEquals(false, shared.access().canManage)
        assertTrue(shared.access().isShared)
        assertEquals(ItemAccess.Own, device.rows.getValue(1).access())
    }

    @Test
    fun `a derived yarn is read-only and cannot be left`() = runTest {
        val device = Device()
        device.apply(
            listOf(project(3, owner = 7, role = "viewer", derived = true)),
            emptyList(),
            json,
        )

        val access = device.rows.getValue(3).access()

        assertTrue(access.derived)
        assertFalse(access.canEdit)
        assertFalse(access.canLeave)
        assertTrue(access.isShared)
    }

    @Test
    fun `a tombstone removes the row and its media, owned rows stay`() = runTest {
        val device = Device()
        device.apply(
            listOf(project(1), project(2, owner = 7, role = "editor"), project(3)),
            emptyList(),
            json,
        )

        val changed = device.apply(emptyList(), listOf(2), json)

        assertTrue(changed)
        assertEquals(listOf(1, 3), device.rows.keys.toList())
        assertEquals(listOf("evict-2"), device.evicted)
    }

    @Test
    fun `only a real difference counts as a change`() = runTest {
        val device = Device()
        assertTrue(device.apply(listOf(project(1)), emptyList(), json))

        // This device's own write coming back unchanged, and a tombstone for an uncached row.
        assertFalse(device.apply(listOf(project(1)), emptyList(), json))
        assertFalse(device.apply(emptyList(), listOf(9), json))

        assertTrue(device.apply(listOf(project(1, updated = 2)), emptyList(), json))
    }

    @Test
    fun `an item that left and came back is deleted first and ends up present`() = runTest {
        val device = Device()
        device.apply(listOf(project(2, owner = 7, role = "viewer")), emptyList(), json)

        device.apply(listOf(project(2, owner = 7, role = "editor")), listOf(2), json)

        assertEquals("editor", device.rows.getValue(2).accessRole)
        // It is coming back, so its media is not evicted.
        assertTrue(device.evicted.isEmpty())
    }

    @Test
    fun `a role change replaces the access on the cached row`() = runTest {
        val device = Device()
        device.apply(listOf(project(2, owner = 7, role = "editor")), emptyList(), json)

        device.apply(listOf(project(2, owner = 7, role = "viewer", updated = 2)), emptyList(), json)

        assertEquals(false, device.rows.getValue(2).access().canEdit)
    }

    @Test
    fun `a transfer away keeps the item as an editor with the new owner`() = runTest {
        val device = Device()
        device.apply(listOf(project(1)), emptyList(), json)

        device.apply(listOf(project(1, owner = 9, role = "editor", updated = 3)), emptyList(), json)

        val row = device.rows.getValue(1)
        assertEquals(9L, row.ownerId)
        assertTrue(row.access().canEdit)
        assertFalse(row.access().canManage)
    }

    @Test
    fun `queued local writes win over a delta and survive a tombstone until replayed`() = runTest {
        val device = Device(pending = setOf(2))
        device.rows[2] = project(2, owner = 7, role = "editor").toEntity(json)
        val before = device.rows.getValue(2)

        device.apply(listOf(project(2, owner = 7, role = "viewer", updated = 5)), emptyList(), json)
        assertEquals(before, device.rows.getValue(2))

        device.apply(emptyList(), listOf(2), json)
        assertTrue("the draft's row stays until the replay learns the 404", 2 in device.rows)
        assertTrue(device.evicted.isEmpty())
    }

    @Test
    fun `replaying the same delta twice leaves the same cache`() = runTest {
        val device = Device()
        val updated = listOf(project(2, owner = 7, role = "viewer"))

        device.apply(updated, listOf(5), json)
        val first = device.rows.toMap()
        device.apply(updated, listOf(5), json)

        assertEquals(first, device.rows)
    }

    @Test
    fun `the cursor loop pages a delta and reports the id set of a full resync`() = runTest {
        val pages =
            mapOf(
                null to
                    V2SyncPageDto(
                        updated = listOf(project(1), project(2, owner = 7, role = "viewer")),
                        serverTime = "T1",
                        hasMore = true,
                        nextCursor = "c1",
                    ),
                "c1" to
                    V2SyncPageDto(
                        updated = listOf(project(3, owner = 7, role = "editor")),
                        serverTime = "T1",
                    ),
            )
        val device = Device()
        val outcome =
            runV2Sync(
                since = null,
                idOf = { it.id },
                fetch = { _, cursor -> pages.getValue(cursor) },
                onPage = { updated, deleted -> device.apply(updated, deleted, json) },
                startAsFullResync = true,
            )

        assertEquals("T1", outcome.serverTime)
        assertEquals(setOf(1, 2, 3), outcome.seenIds)
        assertEquals(listOf(1, 2, 3), device.rows.keys.toList())
        // A full resync removes what the server no longer shows, except queued local writes.
        assertEquals(
            listOf(9),
            staleAfterFullResync(
                localIds = listOf(1, 2, 3, 9, 10),
                seen = outcome.seenIds,
                pendingLocalIds = setOf(10),
            ),
        )
    }

    @Test
    fun `an interrupted run is simply repeated from the old cursor`() = runTest {
        val device = Device()
        var attempt = 0
        val fetch: suspend (String?, String?) -> V2SyncPageDto<ProjectDto> = { _, cursor ->
            attempt++
            if (cursor == null)
                V2SyncPageDto(
                    updated = listOf(project(2, owner = 7, role = "viewer")),
                    serverTime = "T2",
                    hasMore = true,
                    nextCursor = "c",
                )
            else if (attempt == 2) error("connection lost")
            else V2SyncPageDto(updated = emptyList(), deletedIds = listOf(5), serverTime = "T2")
        }
        val run = suspend {
            runV2Sync(
                since = "T1",
                idOf = { it.id },
                fetch = fetch,
                onPage = { u, d -> device.apply(u, d, json) },
            )
        }

        runCatching { run() }
        val outcome = run()

        assertEquals("T2", outcome.serverTime)
        assertEquals(listOf(2), device.rows.keys.toList())
    }

    @Test
    fun `legacy rows without access info are the user's own`() {
        val entity =
            ProjectEntity(
                id = 1,
                name = "Old",
                category = null,
                tagsJson = "[]",
                isFavorite = false,
                updatedAt = 0,
                previewUrl = null,
                detailJson = "{}",
            )

        assertEquals(ItemAccess.Own, entity.access())
        assertNull(entity.accessRole)
        assertTrue(entity.access().canManage)
    }

    @Test
    fun `only shared or derived rows are visible to a member`() {
        assertTrue(isSharedWithMe("viewer", derived = false))
        assertTrue(isSharedWithMe("editor", derived = false))
        assertTrue(isSharedWithMe("viewer", derived = true))
        assertFalse(isSharedWithMe("admin", derived = false))
        assertFalse(isSharedWithMe(null, derived = false))
    }
}
