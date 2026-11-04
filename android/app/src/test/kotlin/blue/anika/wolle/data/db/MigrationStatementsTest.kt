package blue.anika.wolle.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 3 -> 4 migration only adds nullable columns, so nothing queued or cached can be lost. */
class MigrationStatementsTest {
    @Test
    fun `adds the three nullable owner columns to projects and yarns, nothing else`() {
        assertEquals(6, MIGRATION_3_4_STATEMENTS.size)
        assertTrue(MIGRATION_3_4_STATEMENTS.all { it.startsWith("ALTER TABLE ") })
        assertTrue(MIGRATION_3_4_STATEMENTS.none { it.contains("NOT NULL") })
        for (table in listOf("projects", "yarns")) {
            for (column in listOf("ownerId", "ownerName", "ownerAvatarUrl")) {
                assertTrue(
                    "$table.$column",
                    MIGRATION_3_4_STATEMENTS.any {
                        it.contains("`$table`") && it.contains("ADD COLUMN `$column`")
                    },
                )
            }
        }
    }

    @Test
    fun `the migration is 3 to 4`() {
        assertEquals(3, MIGRATION_3_4.startVersion)
        assertEquals(4, MIGRATION_3_4.endVersion)
    }
}
