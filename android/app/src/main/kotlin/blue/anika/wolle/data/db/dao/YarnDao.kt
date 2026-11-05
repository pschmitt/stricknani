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

    @Query("SELECT * FROM yarns WHERE id = :id") suspend fun getById(id: Int): YarnEntity?

    /** Lowest id currently cached, used to mint a fresh negative client-generated temp id. */
    @Query("SELECT MIN(id) FROM yarns") suspend fun minId(): Int?

    @Upsert suspend fun upsertAll(yarns: List<YarnEntity>)

    /** Ids of rows the server knows (positive); negative ids are offline placeholders. */
    @Query("SELECT id FROM yarns WHERE id > 0") suspend fun getServerIds(): List<Int>

    @Query("DELETE FROM yarns WHERE id IN (:ids)") suspend fun deleteByIds(ids: List<Int>)

    /**
     * Ids of rows owned by someone other than [currentUserId] that were not shared with the user
     * (an administrator's view of other people's items). Rows without owner info are the user's
     * own, and shared or derived rows (`viewer`/`editor`) are legitimately cached for everybody;
     * neither ever matches.
     */
    @Query(
        """
        SELECT id FROM yarns
        WHERE ownerId IS NOT NULL AND ownerId != :currentUserId
          AND accessDerived = 0 AND (accessRole IS NULL OR accessRole NOT IN ('viewer', 'editor'))
        """
    )
    suspend fun getForeignIds(currentUserId: Long): List<Int>
}
