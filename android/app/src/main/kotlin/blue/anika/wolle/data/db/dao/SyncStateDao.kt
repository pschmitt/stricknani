package blue.anika.wolle.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import blue.anika.wolle.data.db.entity.SyncStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncStateDao {
    @Query("SELECT cursor FROM sync_state WHERE entityType = :entityType")
    suspend fun getCursor(entityType: String): String?

    /** All entity types' cursors - the Settings screen shows the most recent as "last synced". */
    @Query("SELECT * FROM sync_state") fun observeAll(): Flow<List<SyncStateEntity>>

    @Upsert suspend fun setCursor(state: SyncStateEntity)
}
