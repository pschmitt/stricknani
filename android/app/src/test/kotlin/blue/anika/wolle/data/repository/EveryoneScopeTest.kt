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
 * Admin scope on the device: administrators always sync everything, Mine | Everyone only filters
 * lists, demotion purges, and a fresh (signed-out) database starts from the defaults.
 */
class EveryoneScopeTest {
    private val admin = CurrentUser(id = 1, email = "anika@example.org", isAdmin = true)
    private val member = CurrentUser(id = 1, email = "anika@example.org", isAdmin = false)

    private class FakeSyncState : SyncStateDao {
        val rows = MutableStateFlow<Map<String, String>>(emptyMap())

        override suspend fun getCursor(entityType: String) = rows.value[entityType]

        override fun observeAll(): Flow<List<SyncStateEntity>> = rows.map {
            it.map { (k, v) -> SyncStateEntity(k, v) }
        }

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
            rows
                .filter {
                    it.ownerId != null &&
                        it.ownerId != currentUserId &&
                        !isSharedWithMe(it.accessShareRole ?: it.accessRole, it.accessDerived)
                }
                .map { it.id }
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
            rows
                .filter {
                    it.ownerId != null &&
                        it.ownerId != currentUserId &&
                        !isSharedWithMe(it.accessRole, it.accessDerived)
                }
                .map { it.id }
    }

    private fun project(
        id: Int,
        ownerId: Long?,
        role: String? = null,
        derived: Boolean = false,
        shareRole: String? = null,
    ) =
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
            accessRole = role,
            accessDerived = derived,
            accessShareRole = shareRole,
        )

    private fun yarn(id: Int, ownerId: Long?, role: String? = null, derived: Boolean = false) =
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
            accessRole = role,
            accessDerived = derived,
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
                pendingIds = { type, ids ->
                    ids.filter { it in (pending[type] ?: emptySet()) }.toSet()
                },
            )
        return Fixture(state, projectDao, yarnDao, scope)
    }

    @Test
    fun `administrators always sync everything, everybody else only their own`() {
        assertEquals(SyncScope.ALL, syncScopeFor(isAdmin = true))
        assertEquals(SyncScope.MINE, syncScopeFor(isAdmin = false))
    }

    @Test
    fun `administrators view Everyone until they pick Mine, others never`() {
        assertTrue(viewsEveryone(isAdmin = true, stored = null))
        assertTrue(viewsEveryone(isAdmin = true, stored = "1"))
        assertFalse(viewsEveryone(isAdmin = true, stored = "0"))
        assertFalse(viewsEveryone(isAdmin = false, stored = null))
        assertFalse(viewsEveryone(isAdmin = false, stored = "1"))
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
    fun `an administrator on Mine still syncs all and keeps every cached row`() = runTest {
        val f =
            fixture(
                admin,
                projects = listOf(project(1, 1), project(2, 7)),
                yarns = listOf(yarn(10, 1), yarn(11, 7)),
            )
        f.scope.setEnabled(false)

        assertEquals(SyncScope.ALL, f.scope.forSync())

        assertEquals(listOf(1, 2), f.projects.rows.map { it.id })
        assertEquals(listOf(10, 11), f.yarns.rows.map { it.id })
    }

    @Test
    fun `switching Mine and Everyone is a view filter - no purge, no cursor change`() = runTest {
        val f = fixture(admin, projects = listOf(project(1, 1), project(2, 7)))
        f.state.setCursor(SyncStateEntity("project@all", "ALL-CURSOR"))

        f.scope.setEnabled(false)

        assertEquals(listOf(1, 2), f.projects.rows.map { it.id })
        assertEquals("ALL-CURSOR", f.state.getCursor("project@all"))
        assertEquals(admin.id, f.scope.listFilter.first())
        assertFalse(f.scope.state.first().enabled)
        assertTrue(f.scope.state.first().available)

        f.scope.setEnabled(true)

        assertEquals("ALL-CURSOR", f.state.getCursor("project@all"))
        assertNull(f.scope.listFilter.first())
        assertTrue(f.scope.state.first().enabled)
    }

    @Test
    fun `lists only drop foreign rows on Mine`() {
        val rows = listOf(project(1, 1), project(2, 7), project(3, null))

        assertEquals(listOf(1, 3), rows.visibleTo(hiddenFor = 1L, { it.ownerId }).map { it.id })
        assertEquals(
            listOf(1, 2, 3),
            rows.visibleTo(hiddenFor = null, { it.ownerId }).map { it.id },
        )
    }

    @Test
    fun `an administrator's Mine view hides what they only see as admin, not what is shared`() {
        val rows =
            listOf(
                project(1, 1),
                project(2, 7, role = "admin"),
                project(3, 7, role = "viewer"),
                project(4, 7, role = "viewer", derived = true),
                project(5, 7, role = "admin", shareRole = "viewer"),
            )

        val shown =
            rows.visibleTo(hiddenFor = 1L, { it.ownerId }) {
                isSharedWithMe(it.accessShareRole ?: it.accessRole, it.accessDerived)
            }

        assertEquals(listOf(1, 3, 4, 5), shown.map { it.id })
    }

    @Test
    fun `a member keeps shared and derived rows, only admin leftovers are purged`() = runTest {
        val f =
            fixture(
                member,
                projects =
                    listOf(
                        project(1, 1),
                        project(2, 7, role = "editor"),
                        project(3, 7, role = "admin"),
                        project(4, 7),
                        project(5, 7, role = "admin", shareRole = "editor"),
                    ),
                yarns = listOf(yarn(10, 7, role = "viewer", derived = true), yarn(11, 7)),
            )

        f.scope.forSync()

        assertEquals(listOf(1, 2, 5), f.projects.rows.map { it.id })
        assertEquals(listOf(10), f.yarns.rows.map { it.id })
    }

    @Test
    fun `a non-administrator has no list filter`() = runTest {
        assertNull(fixture(member).scope.listFilter.first())
        assertNull(fixture(user = null).scope.listFilter.first())
    }

    @Test
    fun `upgrading from a Mine install starts a full all-scope sync`() = runTest {
        val f = fixture(admin, projects = listOf(project(1, 1)))
        f.state.setCursor(SyncStateEntity("project", "OLD-MINE"))
        f.state.setCursor(SyncStateEntity("yarn", "OLD-MINE"))
        f.state.setCursor(SyncStateEntity(EVERYONE_FILTER_KEY, "0"))

        assertEquals(SyncScope.ALL, f.scope.forSync())

        // No `@all` cursor: the sync asks for everything. The Mine cursors are gone, so a later
        // demotion also starts from a full sync.
        assertNull(f.state.getCursor(syncCursorKey("project", SyncScope.ALL)))
        assertNull(f.state.getCursor("project"))
        assertNull(f.state.getCursor("yarn"))
        assertEquals("0", f.state.getCursor(EVERYONE_FILTER_KEY))
    }

    @Test
    fun `a demoted administrator's foreign rows are purged except queued writes`() = runTest {
        val f =
            fixture(
                member,
                projects = listOf(project(1, 1), project(2, 7), project(3, 7)),
                yarns = listOf(yarn(11, 7)),
                pending = mapOf("project" to setOf(3)),
            )

        f.scope.forSync()

        assertEquals(listOf(1, 3), f.projects.rows.map { it.id })
        assertTrue(f.yarns.rows.isEmpty())
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
        signedIn.state.setCursor(SyncStateEntity("project@all", "OLD-ACCOUNT"))

        // Sign-out wipes every table (SettingsViewModel.signOut -> clearAllTables): the next
        // account sees no cursor, no stored choice and no rows.
        val next = fixture(admin, state = FakeSyncState())

        assertNull(next.state.getCursor("project@all"))
        assertNull(next.state.getCursor(EVERYONE_FILTER_KEY))
        assertEquals(SyncScope.ALL, next.scope.forSync())
        assertTrue(next.projects.rows.isEmpty())
    }
}
