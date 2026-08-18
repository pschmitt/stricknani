package blue.anika.wolle.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import blue.anika.wolle.data.db.entity.PendingMutationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingMutationDao {
    @Query("SELECT * FROM pending_mutations ORDER BY id ASC") suspend fun getAll(): List<PendingMutationEntity>

    @Query("SELECT COUNT(*) FROM pending_mutations") fun observeCount(): Flow<Int>

    @Query("SELECT * FROM pending_mutations WHERE lastErrorMessage IS NOT NULL ORDER BY id ASC")
    fun observeFailed(): Flow<List<PendingMutationEntity>>

    @Insert suspend fun insert(mutation: PendingMutationEntity): Long

    @Query("DELETE FROM pending_mutations WHERE id = :id") suspend fun delete(id: Long)

    @Query("UPDATE pending_mutations SET lastErrorMessage = :message WHERE id = :id")
    suspend fun setError(id: Long, message: String?)

    /**
     * After a queued create actually lands server-side, later queued mutations for the same
     * not-yet-synced row (an edit made before the create replayed) still point at the old temp
     * id - repoint them at the real server id so they replay against the right resource.
     */
    @Query(
        "UPDATE pending_mutations SET localId = :newLocalId WHERE entityType = :entityType AND localId = :oldLocalId"
    )
    suspend fun reassignLocalId(entityType: String, oldLocalId: Int, newLocalId: Int)
}
