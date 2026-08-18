package blue.anika.wolle.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import blue.anika.wolle.data.db.entity.SyncStateEntity

@Dao
interface SyncStateDao {
    @Query("SELECT cursor FROM sync_state WHERE entityType = :entityType")
    suspend fun getCursor(entityType: String): String?

    @Upsert suspend fun setCursor(state: SyncStateEntity)
}
