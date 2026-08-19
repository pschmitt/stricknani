package blue.anika.wolle.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** [PendingMutationEntity.entityType]. */
object MutationEntityType {
    const val PROJECT = "project"
    const val YARN = "yarn"
}

/** [PendingMutationEntity.operation]. */
object MutationOperation {
    const val CREATE = "create"
    const val UPDATE = "update"
    const val DELETE = "delete"
}

/**
 * One queued offline write, replayed by `sync/WriteReplayWorker.kt` once connectivity returns.
 * [localId] is the id the row is currently known by in Room: a negative client-generated temp id
 * for a still-unsynced [MutationOperation.CREATE] (see `ProjectRepository.createProject`/
 * `YarnRepository.createYarn`), or the real server id otherwise. [payloadJson] is the write request
 * DTO (`ProjectWriteRequest`/`YarnWriteRequest`), verbatim JSON - `null` for a delete.
 */
@Entity(tableName = "pending_mutations")
data class PendingMutationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entityType: String,
    val operation: String,
    val localId: Int,
    val payloadJson: String?,
    val createdAt: Long,
    val lastErrorMessage: String? = null,
    /**
     * SNA-33: set when a replay came back as a definitive, already-resolved conflict (the server
     * rejected it with 409 because a newer edit landed first, or the target was deleted upstream)
     * rather than a transient failure worth retrying. `WriteReplayWorker` excludes these rows from
     * future replay passes - they're kept (not deleted outright) so `lastErrorMessage` stays visible
     * as a "Sync issue" via `PendingMutationDao.observeFailed()`/`HomeViewModel.hasSyncFailures`.
     */
    val isConflict: Boolean = false,
)
