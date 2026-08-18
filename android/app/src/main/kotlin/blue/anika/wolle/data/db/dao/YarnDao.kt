package blue.anika.wolle.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import blue.anika.wolle.data.db.entity.YarnEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface YarnDao {
    @Query("SELECT * FROM yarns ORDER BY updatedAt DESC") fun observeAll(): Flow<List<YarnEntity>>

    @Query("SELECT * FROM yarns WHERE id = :id") fun observeById(id: Int): Flow<YarnEntity?>

    @Upsert suspend fun upsertAll(yarns: List<YarnEntity>)

    @Query("DELETE FROM yarns WHERE id IN (:ids)") suspend fun deleteByIds(ids: List<Int>)
}
