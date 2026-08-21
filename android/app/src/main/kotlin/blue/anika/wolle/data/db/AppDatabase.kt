package blue.anika.wolle.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
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
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao

    abstract fun yarnDao(): YarnDao

    abstract fun categoryDao(): CategoryDao

    abstract fun syncStateDao(): SyncStateDao

    abstract fun pendingMutationDao(): PendingMutationDao
}
