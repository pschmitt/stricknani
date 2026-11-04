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

/** Room 3 -> 4 (admin scope owner columns) and sign-out isolation, on a real SQLite database. */
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
