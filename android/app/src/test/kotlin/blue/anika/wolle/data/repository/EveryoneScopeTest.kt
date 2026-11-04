package blue.anika.wolle.data.repository

import blue.anika.wolle.data.api.CurrentUser
import blue.anika.wolle.data.db.dao.ProjectDao
import blue.anika.wolle.data.db.dao.SyncStateDao
import blue.anika.wolle.data.db.dao.YarnDao
import blue.anika.wolle.data.db.entity.ProjectEntity
import blue.anika.wolle.data.db.entity.SyncStateEntity
import blue.anika.wolle.data.db.entity.YarnEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Admin scope on the device: which scope a sync runs in, which cursor it uses, what the Mine /
 * Everyone switch purges, and that a fresh (signed-out) database starts from the defaults.
 */
class EveryoneScopeTest {
    private val admin = CurrentUser(id = 1, email = "anika@example.org", isAdmin = true)
    private val member = CurrentUser(id = 1, email = "anika@example.org", isAdmin = false)

    private class FakeSyncState : SyncStateDao {
        val rows = MutableStateFlow<Map<String, String>>(emptyMap())

        override suspend fun getCursor(entityType: String) = rows.value[entityType]

        override fun observeAll(): Flow<List<SyncStateEntity>> =
            rows.map { it.map { (k, v) -> SyncStateEntity(k, v) } }

        override suspend fun setCursor(state: SyncStateEntity) {
            rows.value = rows.value + (state.entityType to state.cursor)
        }

        override suspend fun deleteCursors(entityTypes: List<String>) {
            rows.value = rows.value - entityTypes.toSet()
        }
    }

    private class FakeProjects(initial: List<ProjectEntity>) : ProjectDao {
        val rows = initial.toMutableList()

        override fun observeAll(): Flow<List<ProjectEntity>> = MutableStateFlow(rows)

        override fun observeById(id: Int): Flow<ProjectEntity?> =
            MutableStateFlow(rows.firstOrNull { it.id == id })

        override suspend fun getById(id: Int) = rows.firstOrNull { it.id == id }

        override suspend fun minId() = rows.minOfOrNull { it.id }

        override suspend fun upsertAll(projects: List<ProjectEntity>) {
            rows.removeAll { row -> projects.any { it.id == row.id } }
            rows += projects
        }

        override suspend fun getServerIds() = rows.map { it.id }.filter { it > 0 }

        override suspend fun deleteByIds(ids: List<Int>) {
            rows.removeAll { it.id in ids }
        }

        override suspend fun getForeignIds(currentUserId: Long) =
            rows.filter { it.ownerId != null && it.ownerId != currentUserId }.map { it.id }
    }

    private class FakeYarns(initial: List<YarnEntity>) : YarnDao {
        val rows = initial.toMutableList()

        override fun observeAll(): Flow<List<YarnEntity>> = MutableStateFlow(rows)

        override fun observeById(id: Int): Flow<YarnEntity?> =
            MutableStateFlow(rows.firstOrNull { it.id == id })

        override suspend fun getById(id: Int) = rows.firstOrNull { it.id == id }

        override suspend fun minId() = rows.minOfOrNull { it.id }

        override suspend fun upsertAll(yarns: List<YarnEntity>) {
            rows.removeAll { row -> yarns.any { it.id == row.id } }
            rows += yarns
        }

        override suspend fun getServerIds() = rows.map { it.id }.filter { it > 0 }

        override suspend fun deleteByIds(ids: List<Int>) {
            rows.removeAll { it.id in ids }
        }

        override suspend fun getForeignIds(currentUserId: Long) =
            rows.filter { it.ownerId != null && it.ownerId != currentUserId }.map { it.id }
    }

    private fun project(id: Int, ownerId: Long?) =
        ProjectEntity(
            id = id,
            name = "P$id",
            category = null,
            tagsJson = "[]",
            isFavorite = false,
            updatedAt = 0,
            previewUrl = null,
            detailJson = "{}",
            ownerId = ownerId,
            ownerName = ownerId?.let { "Owner$it" },
        )

    private fun yarn(id: Int, ownerId: Long?) =
        YarnEntity(
            id = id,
            name = "Y$id",
            brand = null,
            colorway = null,
            weightCategory = null,
            isFavorite = false,
            updatedAt = 0,
            previewUrl = null,
            detailJson = "{}",
            ownerId = ownerId,
        )

    private class Fixture(
        val state: FakeSyncState,
        val projects: FakeProjects,
        val yarns: FakeYarns,
        val scope: EveryoneScope,
    )

    private fun fixture(
        user: CurrentUser?,
        projects: List<ProjectEntity> = emptyList(),
        yarns: List<YarnEntity> = emptyList(),
        pending: Map<String, Set<Int>> = emptyMap(),
        state: FakeSyncState = FakeSyncState(),
    ): Fixture {
        val projectDao = FakeProjects(projects)
        val yarnDao = FakeYarns(yarns)
        val scope =
            EveryoneScope(
                resolveUser = { user },
                knownUser = { user },
                userFlow = MutableStateFlow(user),
                syncStateDao = state,
                projectDao = projectDao,
                yarnDao = yarnDao,
                pendingIds = { type, ids -> ids.filter { it in (pending[type] ?: emptySet()) }.toSet() },
                inTransaction = { block -> block() },
            )
        return Fixture(state, projectDao, yarnDao, scope)
    }

    @Test
    fun `administrators default to Everyone, everybody else is always Mine`() {
        assertEquals(SyncScope.ALL, effectiveScope(isAdmin = true, stored = null))
        assertEquals(SyncScope.ALL, effectiveScope(isAdmin = true, stored = "1"))
        assertEquals(SyncScope.MINE, effectiveScope(isAdmin = true, stored = "0"))
        assertEquals(SyncScope.MINE, effectiveScope(isAdmin = false, stored = null))
        assertEquals(SyncScope.MINE, effectiveScope(isAdmin = false, stored = "1"))
    }

    @Test
    fun `each scope has its own cursor and Mine keeps the old key`() {
        assertEquals("project", syncCursorKey("project", SyncScope.MINE))
        assertEquals("project@all", syncCursorKey("project", SyncScope.ALL))
        assertEquals("yarn@all", syncCursorKey("yarn", SyncScope.ALL))
        assertNull(SyncScope.MINE.wire)
        assertEquals("all", SyncScope.ALL.wire)
    }

    @Test
    fun `an administrator syncs everybody's items and keeps them`() = runTest {
        val f = fixture(admin, projects = listOf(project(1, 1), project(2, 7)))

        assertEquals(SyncScope.ALL, f.scope.forSync())

        assertEquals(listOf(1, 2), f.projects.rows.map { it.id })
    }

    @Test
    fun `a member never syncs other scopes and foreign leftovers are purged`() = runTest {
        // A demoted administrator: the stored choice says Everyone, the server says member.
        val state = FakeSyncState().also { it.setCursor(SyncStateEntity(EVERYONE_FILTER_KEY, "1")) }
        val f =
            fixture(
                member,
                projects = listOf(project(1, 1), project(2, 7), project(3, null)),
                yarns = listOf(yarn(10, 1), yarn(11, 7)),
                state = state,
            )

        assertEquals(SyncScope.MINE, f.scope.forSync())

        assertEquals(listOf(1, 3), f.projects.rows.map { it.id })
        assertEquals(listOf(10), f.yarns.rows.map { it.id })
    }

    @Test
    fun `an unknown user (legacy server) is Mine and purges nothing`() = runTest {
        val f = fixture(user = null, projects = listOf(project(2, 7)))

        assertEquals(SyncScope.MINE, f.scope.forSync())

        assertEquals(listOf(2), f.projects.rows.map { it.id })
    }

    @Test
    fun `switching to Mine purges other users' rows and resets the all cursors`() = runTest {
        val f =
            fixture(
                admin,
                projects = listOf(project(1, 1), project(2, 7)),
                yarns = listOf(yarn(10, 1), yarn(11, 7)),
            )
        f.state.setCursor(SyncStateEntity("project", "MINE-CURSOR"))
        f.state.setCursor(SyncStateEntity("project@all", "ALL-CURSOR"))
        f.state.setCursor(SyncStateEntity("yarn@all", "ALL-CURSOR"))

        f.scope.setEnabled(false)

        assertEquals(listOf(1), f.projects.rows.map { it.id })
        assertEquals(listOf(10), f.yarns.rows.map { it.id })
        assertNull(f.state.getCursor("project@all"))
        assertNull(f.state.getCursor("yarn@all"))
        // The user's own cursor survives, so Mine resumes where it left off.
        assertEquals("MINE-CURSOR", f.state.getCursor("project"))
        assertEquals(SyncScope.MINE, f.scope.forSync())
        assertFalse(f.scope.state.first().enabled)
        assertTrue(f.scope.state.first().available)
    }

    @Test
    fun `switching to Everyone drops stale all cursors so the next sync is a full one`() = runTest {
        val f = fixture(admin)
        f.state.setCursor(SyncStateEntity(EVERYONE_FILTER_KEY, "0"))
        f.state.setCursor(SyncStateEntity("project@all", "STALE"))

        f.scope.setEnabled(true)

        assertNull(f.state.getCursor("project@all"))
        assertEquals(SyncScope.ALL, f.scope.forSync())
        assertTrue(f.scope.state.first().enabled)
    }

    @Test
    fun `foreign rows with a queued local write survive the purge`() = runTest {
        val f =
            fixture(
                admin,
                projects = listOf(project(2, 7), project(3, 7)),
                yarns = listOf(yarn(11, 7)),
                pending = mapOf("project" to setOf(3), "yarn" to setOf(11)),
            )

        f.scope.setEnabled(false)

        assertEquals(listOf(3), f.projects.rows.map { it.id })
        assertEquals(listOf(11), f.yarns.rows.map { it.id })
    }

    @Test
    fun `the chip is only available to administrators`() = runTest {
        assertTrue(fixture(admin).scope.state.first().available)
        assertFalse(fixture(member).scope.state.first().available)
        assertFalse(fixture(user = null).scope.state.first().available)
    }

    @Test
    fun `a fresh database after sign-out starts from the defaults`() = runTest {
        val signedIn = fixture(admin, projects = listOf(project(2, 7)))
        signedIn.scope.setEnabled(false)
        signedIn.state.setCursor(SyncStateEntity("project", "OLD-ACCOUNT"))

        // Sign-out wipes every table (SettingsViewModel.signOut -> clearAllTables): the next
        // account sees no cursor, no stored choice and no rows.
        val next = fixture(admin, state = FakeSyncState())

        assertNull(next.state.getCursor("project"))
        assertNull(next.state.getCursor(EVERYONE_FILTER_KEY))
        assertEquals(SyncScope.ALL, next.scope.forSync())
        assertTrue(next.projects.rows.isEmpty())
    }
}
