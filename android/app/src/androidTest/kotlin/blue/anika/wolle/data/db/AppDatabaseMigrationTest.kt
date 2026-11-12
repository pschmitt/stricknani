package blue.anika.wolle.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import blue.anika.wolle.data.db.entity.ProjectEntity
import blue.anika.wolle.data.db.entity.SyncStateEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room 3 -> 4 (admin scope owner columns), 4 -> 5 (sharing role, derived and capability flags), 6
 * -> 7 (direct share metadata), and sign-out isolation on a real SQLite database.
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test
    fun migrate3To4_keepsRowsCursorsAndQueuedWrites() {
        helper.createDatabase(DB, 3).apply {
            execSQL(
                "INSERT INTO projects (id, name, category, tagsJson, isFavorite, updatedAt, " +
                    "previewUrl, detailJson) VALUES (5, 'Socks', 'Winter', '[]', 1, 1000, NULL, '{}')"
            )
            execSQL(
                "INSERT INTO yarns (id, name, brand, colorway, weightCategory, isFavorite, " +
                    "updatedAt, previewUrl, detailJson) " +
                    "VALUES (9, 'Merino', 'Drops', NULL, NULL, 0, 2000, NULL, '{}')"
            )
            execSQL("INSERT INTO sync_state (entityType, cursor) VALUES ('project', 'T1')")
            execSQL(
                "INSERT INTO pending_mutations (entityType, operation, localId, payloadJson, " +
                    "createdAt, lastErrorMessage, isConflict) " +
                    "VALUES ('project', 'update', 5, '{}', 3000, NULL, 0)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(DB, 4, true, MIGRATION_3_4)

        db.query("SELECT name, ownerId, ownerName, ownerAvatarUrl, isFavorite FROM projects").use {
            c ->
            assertTrue(c.moveToFirst())
            assertEquals("Socks", c.getString(0))
            // No owner info means "the signed-in user's own".
            assertTrue(c.isNull(1) && c.isNull(2) && c.isNull(3))
            assertEquals(1, c.getInt(4))
        }
        db.query("SELECT name, ownerId FROM yarns").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Merino", c.getString(0))
            assertTrue(c.isNull(1))
        }
        db.query("SELECT cursor FROM sync_state WHERE entityType = 'project'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("T1", c.getString(0))
        }
        db.query("SELECT COUNT(*) FROM pending_mutations").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
    }

    @Test
    fun migrate4To5_keepsOwnRowsAsOwnAndKeepsSharedOwnerInfo() {
        helper.createDatabase(DB, 4).apply {
            execSQL(
                "INSERT INTO projects (id, name, category, tagsJson, isFavorite, updatedAt, " +
                    "previewUrl, detailJson, ownerId, ownerName, ownerAvatarUrl) " +
                    "VALUES (5, 'Socks', NULL, '[]', 0, 1000, NULL, '{}', NULL, NULL, NULL)"
            )
            execSQL(
                "INSERT INTO projects (id, name, category, tagsJson, isFavorite, updatedAt, " +
                    "previewUrl, detailJson, ownerId, ownerName, ownerAvatarUrl) " +
                    "VALUES (6, 'Theirs', NULL, '[]', 0, 1000, NULL, '{}', 7, 'Anna', '/a')"
            )
            execSQL(
                "INSERT INTO yarns (id, name, brand, colorway, weightCategory, isFavorite, " +
                    "updatedAt, previewUrl, detailJson) " +
                    "VALUES (9, 'Merino', NULL, NULL, NULL, 0, 2000, NULL, '{}')"
            )
            execSQL("INSERT INTO sync_state (entityType, cursor) VALUES ('project', 'T1')")
            execSQL(
                "INSERT INTO pending_mutations (entityType, operation, localId, payloadJson, " +
                    "createdAt, lastErrorMessage, isConflict) " +
                    "VALUES ('project', 'update', 5, '{}', 3000, NULL, 0)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(DB, 5, true, MIGRATION_4_5)

        db.query(
                "SELECT id, ownerId, ownerName, accessRole, accessDerived, canEdit, canManage " +
                    "FROM projects ORDER BY id"
            )
            .use { c ->
                assertTrue(c.moveToFirst())
                // Own row: no access info, derived off - exactly the owner's full access.
                assertEquals(5, c.getInt(0))
                assertTrue(c.isNull(1) && c.isNull(3) && c.isNull(5) && c.isNull(6))
                assertEquals(0, c.getInt(4))
                assertTrue(c.moveToNext())
                assertEquals(7, c.getInt(1))
                assertEquals("Anna", c.getString(2))
                assertTrue(c.isNull(3))
            }
        db.query("SELECT accessDerived, accessRole FROM yarns").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
            assertTrue(c.isNull(1))
        }
        db.query("SELECT cursor FROM sync_state WHERE entityType = 'project'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("T1", c.getString(0))
        }
        db.query("SELECT COUNT(*) FROM pending_mutations").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
    }

    @Test
    fun migrateFrom3_chainsBothMigrations() {
        helper.createDatabase(DB, 3).apply {
            execSQL(
                "INSERT INTO projects (id, name, category, tagsJson, isFavorite, updatedAt, " +
                    "previewUrl, detailJson) VALUES (5, 'Socks', NULL, '[]', 0, 1000, NULL, '{}')"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(DB, 5, true, MIGRATION_3_4, MIGRATION_4_5)

        db.query("SELECT name, ownerId, accessRole FROM projects").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Socks", c.getString(0))
            assertTrue(c.isNull(1) && c.isNull(2))
        }
    }

    @Test
    fun migrateFrom5_marksCachedProjectsInProgressAndKeepsTheQueue() {
        helper.createDatabase(DB, 5).apply {
            execSQL(
                "INSERT INTO projects (id, name, category, tagsJson, isFavorite, updatedAt, " +
                    "previewUrl, detailJson, accessDerived) " +
                    "VALUES (5, 'Socks', NULL, '[]', 0, 1000, NULL, '{}', 0)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(DB, 6, true, MIGRATION_5_6)

        db.query("SELECT name, status FROM projects").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Socks", c.getString(0))
            assertEquals("in_progress", c.getString(1))
        }
    }

    @Test
    fun migrate6To7_refetchesProjectScopesAndKeepsCachedRowsQueueAndOtherCursors() {
        helper.createDatabase(DB, 6).apply {
            execSQL(
                "INSERT INTO projects (id, name, category, tagsJson, isFavorite, status, updatedAt, " +
                    "previewUrl, detailJson, ownerId, ownerName, ownerAvatarUrl, accessRole, " +
                    "accessDerived, canEdit, canManage) " +
                    "VALUES (5, 'Admin shared', NULL, '[]', 0, 'in_progress', 1000, NULL, '{}', " +
                    "7, 'Owner', NULL, 'admin', 0, 1, 1)"
            )
            execSQL(
                "INSERT INTO projects (id, name, category, tagsJson, isFavorite, status, updatedAt, " +
                    "previewUrl, detailJson, ownerId, ownerName, ownerAvatarUrl, accessRole, " +
                    "accessDerived, canEdit, canManage) " +
                    "VALUES (6, 'Finished', NULL, '[]', 1, 'done', 2000, NULL, '{}', NULL, NULL, " +
                    "NULL, 'owner', 0, 1, 1)"
            )
            execSQL(
                "INSERT INTO yarns (id, name, brand, colorway, weightCategory, isFavorite, " +
                    "updatedAt, previewUrl, detailJson) " +
                    "VALUES (9, 'Linen', NULL, NULL, NULL, 0, 3000, NULL, '{}')"
            )
            execSQL(
                "INSERT INTO pending_mutations (entityType, operation, localId, payloadJson, " +
                    "createdAt, lastErrorMessage, isConflict) " +
                    "VALUES ('project', 'update', 5, '{\"name\":\"queued\"}', 4000, NULL, 0)"
            )
            listOf(
                    "project" to "P1",
                    "project@all" to "P2",
                    "yarn" to "Y1",
                    "yarn@all" to "Y2",
                    "categories" to "C1",
                    "everyone_filter" to "0",
                )
                .forEach { (entity, cursor) ->
                    execSQL(
                        "INSERT INTO sync_state (entityType, cursor) VALUES ('$entity', '$cursor')"
                    )
                }
            close()
        }

        val db = helper.runMigrationsAndValidate(DB, 7, true, MIGRATION_6_7)

        db.query(
                "SELECT id, name, status, accessRole, accessDerived, canEdit, canManage, " +
                    "accessShareRole FROM projects ORDER BY id"
            )
            .use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(5, c.getInt(0))
                assertEquals("Admin shared", c.getString(1))
                assertEquals("in_progress", c.getString(2))
                assertEquals("admin", c.getString(3))
                assertEquals(0, c.getInt(4))
                assertEquals(1, c.getInt(5))
                assertEquals(1, c.getInt(6))
                assertTrue(c.isNull(7))
                assertTrue(c.moveToNext())
                assertEquals(6, c.getInt(0))
                assertEquals("Finished", c.getString(1))
                assertEquals("done", c.getString(2))
                assertEquals("owner", c.getString(3))
                assertTrue(!c.moveToNext())
                assertTrue(c.isAfterLast())
            }
        db.query("SELECT name FROM yarns").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Linen", c.getString(0))
            assertTrue(!c.moveToNext())
        }
        db.query("SELECT entityType, operation, localId, payloadJson FROM pending_mutations").use {
            c ->
            assertTrue(c.moveToFirst())
            assertEquals("project", c.getString(0))
            assertEquals("update", c.getString(1))
            assertEquals(5, c.getInt(2))
            assertEquals("{\"name\":\"queued\"}", c.getString(3))
            assertTrue(!c.moveToNext())
        }
        db.query("SELECT COUNT(*) FROM sync_state WHERE entityType IN ('project', 'project@all')")
            .use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0))
            }
        db.query("SELECT COUNT(*) FROM projects").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(2, c.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM yarns").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
        db.query("SELECT entityType, cursor FROM sync_state ORDER BY entityType").use { c ->
            val retained = mutableMapOf<String, String>()
            while (c.moveToNext()) retained[c.getString(0)] = c.getString(1)
            assertEquals(
                mapOf(
                    "categories" to "C1",
                    "everyone_filter" to "0",
                    "yarn" to "Y1",
                    "yarn@all" to "Y2",
                ),
                retained,
            )
        }
    }

    @Test
    fun clearAllTables_removesSharedRowsToo_soAccountsNeverMix() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            db.projectDao()
                .upsertAll(
                    listOf(
                        ProjectEntity(
                            id = 3,
                            name = "Shared with me",
                            category = null,
                            tagsJson = "[]",
                            isFavorite = false,
                            updatedAt = 1,
                            previewUrl = null,
                            detailJson = "{}",
                            ownerId = 7,
                            ownerName = "Anna",
                            accessRole = "viewer",
                            canEdit = false,
                            canManage = false,
                        )
                    )
                )
            // A member keeps shared rows, an administrator-only view is what gets purged.
            assertTrue(db.projectDao().getForeignIds(currentUserId = 1).isEmpty())

            db.clearAllTables()

            assertTrue(db.projectDao().getServerIds().isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun projectForeignIds_keepAdminDirectSharesAndReturnAdminOnlyRows() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            db.projectDao()
                .upsertAll(
                    listOf(
                        ProjectEntity(
                            id = 7,
                            name = "Shared with admin",
                            category = null,
                            tagsJson = "[]",
                            isFavorite = false,
                            updatedAt = 1,
                            previewUrl = null,
                            detailJson = "{}",
                            ownerId = 2,
                            accessRole = "admin",
                            accessShareRole = "viewer",
                        ),
                        ProjectEntity(
                            id = 8,
                            name = "Admin only",
                            category = null,
                            tagsJson = "[]",
                            isFavorite = false,
                            updatedAt = 1,
                            previewUrl = null,
                            detailJson = "{}",
                            ownerId = 3,
                            accessRole = "admin",
                        ),
                    )
                )

            assertEquals(listOf(8), db.projectDao().getForeignIds(currentUserId = 1))
        } finally {
            db.close()
        }
    }

    @Test
    fun clearAllTables_removesForeignItemsCursorsQueueAndTheFilterChoice() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            db.projectDao()
                .upsertAll(
                    listOf(
                        ProjectEntity(
                            id = 2,
                            name = "Theirs",
                            category = null,
                            tagsJson = "[]",
                            isFavorite = false,
                            updatedAt = 1,
                            previewUrl = null,
                            detailJson = "{}",
                            ownerId = 7,
                            ownerName = "Anika",
                        )
                    )
                )
            db.syncStateDao().setCursor(SyncStateEntity("project@all", "T9"))
            db.syncStateDao().setCursor(SyncStateEntity("everyone_filter", "1"))
            assertEquals(listOf(2), db.projectDao().getForeignIds(currentUserId = 1))

            // What SettingsViewModel.signOut / account deletion do.
            db.clearAllTables()

            assertTrue(db.projectDao().getServerIds().isEmpty())
            assertNull(db.syncStateDao().getCursor("project@all"))
            assertNull(db.syncStateDao().getCursor("everyone_filter"))
        } finally {
            db.close()
        }
    }

    private companion object {
        const val DB = "migration-test"
    }
}
