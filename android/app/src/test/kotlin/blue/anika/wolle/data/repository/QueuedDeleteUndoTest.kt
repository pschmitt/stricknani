package blue.anika.wolle.data.repository

import blue.anika.wolle.data.db.dao.PendingMutationDao
import blue.anika.wolle.data.db.entity.MutationEntityType
import blue.anika.wolle.data.db.entity.MutationOperation
import blue.anika.wolle.data.db.entity.PendingMutationEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The outbox half of Undo for deletes made offline, against an in-memory outbox. */
class QueuedDeleteUndoTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val dao = FakeOutbox()

    private fun queueDelete(type: String, id: Int, snapshot: String?) = runBlocking {
        dao.insert(
            PendingMutationEntity(
                entityType = type,
                operation = MutationOperation.DELETE,
                localId = id,
                payloadJson = snapshot,
                createdAt = 1,
            )
        )
    }

    @Test
    fun `undo of a still queued delete removes it and returns the snapshot`() = runBlocking {
        queueDelete(MutationEntityType.PROJECT, 7, """{"id":7}""")

        val snapshot = QueuedDeleteUndo.cancelQueuedDelete(dao, MutationEntityType.PROJECT, 7)

        assertEquals("""{"id":7}""", snapshot)
        assertTrue("nothing is left to send", dao.rows.isEmpty())
        assertFalse(QueuedDeleteUndo.hasQueuedDelete(dao, MutationEntityType.PROJECT, 7))
    }

    @Test
    fun `undo leaves other entities and other operations in the queue`() = runBlocking {
        queueDelete(MutationEntityType.PROJECT, 7, "p7")
        queueDelete(MutationEntityType.PROJECT, 8, "p8")
        queueDelete(MutationEntityType.YARN, 7, "y7")
        dao.insert(
            PendingMutationEntity(
                entityType = MutationEntityType.PROJECT,
                operation = MutationOperation.UPDATE,
                localId = 7,
                payloadJson = "{}",
                createdAt = 2,
            )
        )

        assertEquals("p7", QueuedDeleteUndo.cancelQueuedDelete(dao, MutationEntityType.PROJECT, 7))

        assertEquals(
            listOf(
                MutationEntityType.PROJECT to 8,
                MutationEntityType.YARN to 7,
                MutationEntityType.PROJECT to 7,
            ),
            dao.rows.map { it.entityType to it.localId },
        )
        assertNull(QueuedDeleteUndo.cancelQueuedDelete(dao, MutationEntityType.PROJECT, 7))
    }

    @Test
    fun `a delete that was already sent cannot be cancelled`() = runBlocking {
        assertNull(QueuedDeleteUndo.cancelQueuedDelete(dao, MutationEntityType.YARN, 3))
        assertFalse(QueuedDeleteUndo.hasQueuedDelete(dao, MutationEntityType.YARN, 3))
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `a delete queued without a snapshot stays queued`() = runBlocking {
        queueDelete(MutationEntityType.PROJECT, 7, null)

        assertNull(QueuedDeleteUndo.cancelQueuedDelete(dao, MutationEntityType.PROJECT, 7))

        assertEquals(1, dao.rows.size)
        assertTrue(QueuedDeleteUndo.hasQueuedDelete(dao, MutationEntityType.PROJECT, 7))
    }

    @Test
    fun `a conflicted delete is not cancelled`() = runBlocking {
        queueDelete(MutationEntityType.PROJECT, 7, "p7")
        dao.markConflict(dao.rows.single().id, "gone")

        assertNull(QueuedDeleteUndo.cancelQueuedDelete(dao, MutationEntityType.PROJECT, 7))
    }

    @Test
    fun `a restore is queued behind the delete once and replays the trash item`() = runBlocking {
        queueDelete(MutationEntityType.PROJECT, 7, null)
        val ref = TrashRef(TrashKind.Project, 7)

        QueuedDeleteUndo.queueRestore(dao, json, ref, now = 5)
        QueuedDeleteUndo.queueRestore(dao, json, ref, now = 6)

        val replay = dao.getAll()
        assertEquals(
            listOf(MutationOperation.DELETE, MutationOperation.TRASH_RESTORE),
            replay.map { it.operation },
        )
        val restore = replay.last()
        assertEquals(MutationEntityType.TRASH, restore.entityType)
        assertEquals(7, restore.localId)
        assertEquals(
            QueuedRestore("project", 7),
            json.decodeFromString<QueuedRestore>(restore.payloadJson!!),
        )
    }

    @Test
    fun `restores of different kinds with the same id are both kept`() = runBlocking {
        QueuedDeleteUndo.queueRestore(dao, json, TrashRef(TrashKind.Project, 7))
        QueuedDeleteUndo.queueRestore(dao, json, TrashRef(TrashKind.Yarn, 7))

        assertEquals(2, dao.rows.size)
    }
}

/** An in-memory [PendingMutationDao] with the same ordering and filtering as the Room queries. */
private class FakeOutbox : PendingMutationDao {
    val rows = mutableListOf<PendingMutationEntity>()
    private var nextId = 1L
    private val count = MutableStateFlow(0)

    override suspend fun getAll() = rows.filter { !it.isConflict }.sortedBy { it.id }

    override suspend fun getById(id: Long) = rows.firstOrNull { it.id == id }

    override suspend fun getAllReplayable(entityType: String, operation: String, localId: Int) =
        getAll().filter {
            it.entityType == entityType && it.operation == operation && it.localId == localId
        }

    override suspend fun getReplayable(entityType: String, operation: String, localId: Int) =
        getAllReplayable(entityType, operation, localId).firstOrNull()

    override suspend fun getReplayableLocalIds(entityType: String, localIds: List<Int>) =
        getAll().filter { it.entityType == entityType && it.localId in localIds }.map { it.localId }

    override fun observeCount(): Flow<Int> = count

    override fun observeFailed(): Flow<List<PendingMutationEntity>> =
        MutableStateFlow(rows.filter { it.lastErrorMessage != null })

    override suspend fun insert(mutation: PendingMutationEntity): Long {
        val id = nextId++
        rows += mutation.copy(id = id)
        return id
    }

    override suspend fun delete(id: Long) {
        rows.removeAll { it.id == id }
    }

    override suspend fun setError(id: Long, message: String?) =
        update(id) {
            it.copy(lastErrorMessage = message)
        }

    override suspend fun replacePayload(id: Long, payloadJson: String) =
        update(id) {
            it.copy(payloadJson = payloadJson, lastErrorMessage = null)
        }

    override suspend fun deleteResolvedConflicts(entityType: String, localId: Int) {
        rows.removeAll { it.entityType == entityType && it.localId == localId && it.isConflict }
    }

    override suspend fun dismissConflict(id: Long) {
        rows.removeAll { it.id == id && it.isConflict }
    }

    override suspend fun markConflict(id: Long, message: String) =
        update(id) {
            it.copy(lastErrorMessage = message, isConflict = true)
        }

    override suspend fun reassignLocalId(entityType: String, oldLocalId: Int, newLocalId: Int) {
        rows.replaceAll {
            if (it.entityType == entityType && it.localId == oldLocalId) {
                it.copy(localId = newLocalId)
            } else it
        }
    }

    private fun update(id: Long, change: (PendingMutationEntity) -> PendingMutationEntity) {
        rows.replaceAll { if (it.id == id) change(it) else it }
    }
}
