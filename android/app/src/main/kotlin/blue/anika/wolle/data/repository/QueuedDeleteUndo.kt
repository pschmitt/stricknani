package blue.anika.wolle.data.repository

import blue.anika.wolle.data.db.dao.PendingMutationDao
import blue.anika.wolle.data.db.entity.MutationEntityType
import blue.anika.wolle.data.db.entity.MutationOperation
import blue.anika.wolle.data.db.entity.PendingMutationEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What a queued restore replays: which trash item to take out of the trash. */
@Serializable data class QueuedRestore(val kind: String, val id: Long)

/**
 * Undo of a delete made while offline (or before the write queue reached the server). Works on the
 * outbox directly and has no Android dependencies, so it is unit-tested with a fake
 * [PendingMutationDao].
 *
 * * A delete that is **still queued** was never sent: [cancelQueuedDelete] removes it from the
 *   queue and hands back the snapshot of the cache row taken when the user deleted, which the
 *   repository puts back. The server never heard of it.
 * * A delete that **was sent** is in the trash: Undo calls the restore endpoint, and when there is
 *   no connection [queueRestore] queues that restore so it goes out as soon as the app is online.
 */
object QueuedDeleteUndo {
    /**
     * Removes the queued DELETE of [entityType]/[id] and returns its snapshot. Null when no such
     * delete is queued, or when it has no snapshot (queued by an older app version): the delete is
     * then left alone and the caller falls back to restoring after it was sent.
     */
    suspend fun cancelQueuedDelete(
        dao: PendingMutationDao,
        entityType: String,
        id: Int,
    ): String? {
        val queued = dao.getReplayable(entityType, MutationOperation.DELETE, id) ?: return null
        val snapshot = queued.payloadJson ?: return null
        dao.delete(queued.id)
        return snapshot
    }

    /** Whether a delete of [entityType]/[id] still waits in the queue. */
    suspend fun hasQueuedDelete(dao: PendingMutationDao, entityType: String, id: Int): Boolean =
        dao.getReplayable(entityType, MutationOperation.DELETE, id) != null

    /**
     * Queues "restore [ref] from the trash" behind everything queued so far (so after the delete
     * that put it there). Repeated Undo taps queue one restore only.
     */
    suspend fun queueRestore(
        dao: PendingMutationDao,
        json: Json,
        ref: TrashRef,
        now: Long = System.currentTimeMillis(),
    ) {
        if (ref.id !in Int.MIN_VALUE..Int.MAX_VALUE) return
        val localId = ref.id.toInt()
        val already =
            dao.getAllReplayable(MutationEntityType.TRASH, MutationOperation.TRASH_RESTORE, localId)
                .any {
                    json.decodeFromString<QueuedRestore>(it.payloadJson.orEmpty()).kind == ref.kind
                }
        if (already) return
        dao.insert(
            PendingMutationEntity(
                entityType = MutationEntityType.TRASH,
                operation = MutationOperation.TRASH_RESTORE,
                localId = localId,
                payloadJson = json.encodeToString(QueuedRestore(ref.kind, ref.id)),
                createdAt = now,
            )
        )
    }
}
