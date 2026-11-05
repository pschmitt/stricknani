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

/** The 4 -> 5 migration (sharing) only adds columns whose defaults mean "the user's own". */
class MigrationStatements45Test {
    @Test
    fun `adds role, derived and the two capability flags to projects and yarns, nothing else`() {
        assertEquals(8, MIGRATION_4_5_STATEMENTS.size)
        assertTrue(MIGRATION_4_5_STATEMENTS.all { it.startsWith("ALTER TABLE ") })
        for (table in listOf("projects", "yarns")) {
            for (column in listOf("accessRole", "accessDerived", "canEdit", "canManage")) {
                assertTrue(
                    "$table.$column",
                    MIGRATION_4_5_STATEMENTS.any {
                        it.contains("`$table`") && it.contains("ADD COLUMN `$column`")
                    },
                )
            }
        }
    }

    @Test
    fun `existing rows read as the user's own, so nothing cached or queued changes`() {
        val derived = MIGRATION_4_5_STATEMENTS.filter { it.contains("`accessDerived`") }
        assertTrue(derived.all { it.contains("NOT NULL DEFAULT 0") })
        val others = MIGRATION_4_5_STATEMENTS.filterNot { it.contains("`accessDerived`") }
        assertTrue(others.none { it.contains("NOT NULL") })
        assertTrue(MIGRATION_4_5_STATEMENTS.none { it.contains("DROP") || it.contains("DELETE") })
    }

    @Test
    fun `the migration is 4 to 5`() {
        assertEquals(4, MIGRATION_4_5.startVersion)
        assertEquals(5, MIGRATION_4_5.endVersion)
    }
}
