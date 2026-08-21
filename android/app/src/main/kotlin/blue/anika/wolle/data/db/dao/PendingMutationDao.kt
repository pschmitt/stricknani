package blue.anika.wolle.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import blue.anika.wolle.data.db.entity.PendingMutationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingMutationDao {
    /** Excludes mutations already resolved as a conflict (SNA-33) - see [markConflict]. */
    @Query("SELECT * FROM pending_mutations WHERE isConflict = 0 ORDER BY id ASC")
    suspend fun getAll(): List<PendingMutationEntity>

    @Query("SELECT * FROM pending_mutations WHERE id = :id")
    suspend fun getById(id: Long): PendingMutationEntity?

    /**
     * Returns the first replayable mutation of one kind for an entity. Repositories use this to
     * coalesce repeated local edits without changing the mutation's insertion position.
     */
    @Query(
        """
        SELECT * FROM pending_mutations
        WHERE entityType = :entityType
          AND operation = :operation
          AND localId = :localId
          AND isConflict = 0
        ORDER BY id ASC
        LIMIT 1
        """
    )
    suspend fun getReplayable(
        entityType: String,
        operation: String,
        localId: Int,
    ): PendingMutationEntity?

    /** IDs with a non-resolved local mutation that must not be overwritten by delta sync. */
    @Query(
        """
        SELECT DISTINCT localId FROM pending_mutations
        WHERE entityType = :entityType
          AND isConflict = 0
          AND localId IN (:localIds)
        """
    )
    suspend fun getReplayableLocalIds(entityType: String, localIds: List<Int>): List<Int>

    @Query("SELECT COUNT(*) FROM pending_mutations WHERE isConflict = 0")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM pending_mutations WHERE lastErrorMessage IS NOT NULL ORDER BY id ASC")
    fun observeFailed(): Flow<List<PendingMutationEntity>>

    @Insert suspend fun insert(mutation: PendingMutationEntity): Long

    @Query("DELETE FROM pending_mutations WHERE id = :id") suspend fun delete(id: Long)

    @Query("UPDATE pending_mutations SET lastErrorMessage = :message WHERE id = :id")
    suspend fun setError(id: Long, message: String?)

    /** Replaces a coalesced edit and clears an old transient failure for that edit. */
    @Query(
        """
        UPDATE pending_mutations
        SET payloadJson = :payloadJson, lastErrorMessage = NULL
        WHERE id = :id
        """
    )
    suspend fun replacePayload(id: Long, payloadJson: String)

    /** A newer local edit supersedes an already-resolved conflict for the same entity. */
    @Query(
        """
        DELETE FROM pending_mutations
        WHERE entityType = :entityType AND localId = :localId AND isConflict = 1
        """
    )
    suspend fun deleteResolvedConflicts(entityType: String, localId: Int)

    /** Dismisses a conflict after its server version has already been adopted into the cache. */
    @Query("DELETE FROM pending_mutations WHERE id = :id AND isConflict = 1")
    suspend fun dismissConflict(id: Long)

    /**
     * Marks a mutation as a definitively resolved conflict (SNA-33) - stops `WriteReplayWorker`
     * from retrying it (see [getAll]'s filter) while keeping it visible as a "Sync issue" via
     * [observeFailed] until the user next dismisses/overwrites it.
     */
    @Query(
        "UPDATE pending_mutations SET lastErrorMessage = :message, isConflict = 1 WHERE id = :id"
    )
    suspend fun markConflict(id: Long, message: String)

    /**
     * After a queued create actually lands server-side, later queued mutations for the same
     * not-yet-synced row (an edit made before the create replayed) still point at the old temp id -
     * repoint them at the real server id so they replay against the right resource.
     */
    @Query(
        """
        UPDATE pending_mutations SET localId = :newLocalId
        WHERE entityType = :entityType AND localId = :oldLocalId
        """
    )
    suspend fun reassignLocalId(entityType: String, oldLocalId: Int, newLocalId: Int)
}
