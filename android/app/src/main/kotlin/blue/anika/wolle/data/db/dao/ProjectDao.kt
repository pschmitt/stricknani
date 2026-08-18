package blue.anika.wolle.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import blue.anika.wolle.data.db.entity.ProjectEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id") fun observeById(id: Int): Flow<ProjectEntity?>

    @Query("SELECT * FROM projects WHERE id = :id") suspend fun getById(id: Int): ProjectEntity?

    /** Lowest id currently cached, used to mint a fresh negative client-generated temp id. */
    @Query("SELECT MIN(id) FROM projects") suspend fun minId(): Int?

    @Upsert suspend fun upsertAll(projects: List<ProjectEntity>)

    @Query("DELETE FROM projects WHERE id IN (:ids)") suspend fun deleteByIds(ids: List<Int>)
}
