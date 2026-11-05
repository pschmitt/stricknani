package blue.anika.wolle.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import blue.anika.wolle.data.db.dao.CategoryDao
import blue.anika.wolle.data.db.dao.PendingMutationDao
import blue.anika.wolle.data.db.dao.ProjectDao
import blue.anika.wolle.data.db.dao.SyncStateDao
import blue.anika.wolle.data.db.dao.YarnDao
import blue.anika.wolle.data.db.entity.CategoryEntity
import blue.anika.wolle.data.db.entity.PendingMutationEntity
import blue.anika.wolle.data.db.entity.ProjectEntity
import blue.anika.wolle.data.db.entity.SyncStateEntity
import blue.anika.wolle.data.db.entity.YarnEntity

@Database(
    entities =
        [
            ProjectEntity::class,
            YarnEntity::class,
            CategoryEntity::class,
            SyncStateEntity::class,
            PendingMutationEntity::class,
        ],
    version = 5,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao

    abstract fun yarnDao(): YarnDao

    abstract fun categoryDao(): CategoryDao

    abstract fun syncStateDao(): SyncStateDao

    abstract fun pendingMutationDao(): PendingMutationDao
}

/**
 * 3 -> 4 (admin scope): projects and yarns remember their owner. Existing rows keep NULL owner
 * columns, which means "the signed-in user's own" - exactly what they were before. Queued writes
 * and sync cursors are untouched, so no offline edit is lost by the upgrade.
 */
val MIGRATION_3_4: Migration =
    object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MIGRATION_3_4_STATEMENTS.forEach(db::execSQL)
        }
    }

internal val MIGRATION_3_4_STATEMENTS: List<String> =
    listOf("projects", "yarns").flatMap { table ->
        listOf(
            "ALTER TABLE `$table` ADD COLUMN `ownerId` INTEGER",
            "ALTER TABLE `$table` ADD COLUMN `ownerName` TEXT",
            "ALTER TABLE `$table` ADD COLUMN `ownerAvatarUrl` TEXT",
        )
    }

/**
 * 4 -> 5 (sharing): projects and yarns remember the caller's access (`access.role`, `derived`,
 * `can_edit`, `can_manage`). Existing rows keep NULL / 0, which means "the signed-in user's own" -
 * exactly what they were before - so no cached item, cursor or queued write changes. Owner columns
 * (added in 3 -> 4) are reused for the owner of a shared item.
 */
val MIGRATION_4_5: Migration =
    object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MIGRATION_4_5_STATEMENTS.forEach(db::execSQL)
        }
    }

internal val MIGRATION_4_5_STATEMENTS: List<String> =
    listOf("projects", "yarns").flatMap { table ->
        listOf(
            "ALTER TABLE `$table` ADD COLUMN `accessRole` TEXT",
            "ALTER TABLE `$table` ADD COLUMN `accessDerived` INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE `$table` ADD COLUMN `canEdit` INTEGER",
            "ALTER TABLE `$table` ADD COLUMN `canManage` INTEGER",
        )
    }
