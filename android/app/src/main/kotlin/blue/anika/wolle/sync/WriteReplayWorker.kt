package blue.anika.wolle.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import blue.anika.wolle.data.api.dto.ProjectWriteRequest
import blue.anika.wolle.data.api.dto.YarnWriteRequest
import blue.anika.wolle.data.db.dao.PendingMutationDao
import blue.anika.wolle.data.db.entity.MutationEntityType
import blue.anika.wolle.data.db.entity.MutationOperation
import blue.anika.wolle.data.db.entity.PendingMutationEntity
import blue.anika.wolle.data.repository.ProjectRepository
import blue.anika.wolle.data.repository.YarnRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Flushes `PendingMutationDao`'s outbox (SNA-8) once connectivity returns - see
 * `ProjectRepository.createProject`/`updateProject`/`deleteProject` (and the yarn equivalents) for
 * where mutations get queued. Replays strictly in insertion order (`getAll()` is `ORDER BY id ASC`)
 * since an UPDATE/DELETE queued against a still-unsynced CREATE's temp id depends on that CREATE
 * having replayed first to learn the real server id.
 *
 * A replay failure (e.g. the row was deleted server-side in the meantime, or the device is still
 * offline) leaves the mutation queued with its error message recorded (`lastErrorMessage`, surfaced
 * as a conflict/pending-changes indicator by the UI) rather than silently dropping the local edit -
 * it retries on the next pass.
 */
@HiltWorker
class WriteReplayWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val pendingMutationDao: PendingMutationDao,
    private val projectRepository: ProjectRepository,
    private val yarnRepository: YarnRepository,
    private val json: Json,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        var anyFailure = false
        for (mutation in pendingMutationDao.getAll()) {
            try {
                replay(mutation)
                pendingMutationDao.delete(mutation.id)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                Timber.w(
                    failure,
                    "Replay failed for mutation ${mutation.id} (${mutation.entityType}/${mutation.operation})",
                )
                pendingMutationDao.setError(mutation.id, failure.message ?: "Sync failed")
                anyFailure = true
            }
        }
        return if (anyFailure) Result.retry() else Result.success()
    }

    private suspend fun replay(mutation: PendingMutationEntity) {
        when (mutation.entityType) {
            MutationEntityType.PROJECT -> replayProject(mutation)
            MutationEntityType.YARN -> replayYarn(mutation)
        }
    }

    private suspend fun replayProject(mutation: PendingMutationEntity) {
        when (mutation.operation) {
            MutationOperation.CREATE -> {
                val request = json.decodeFromString<ProjectWriteRequest>(mutation.payloadJson!!)
                val realId = projectRepository.replayCreate(mutation.localId, request)
                pendingMutationDao.reassignLocalId(
                    MutationEntityType.PROJECT,
                    mutation.localId,
                    realId,
                )
            }
            MutationOperation.UPDATE -> {
                val request = json.decodeFromString<ProjectWriteRequest>(mutation.payloadJson!!)
                projectRepository.replayUpdate(mutation.localId, request)
            }
            MutationOperation.DELETE -> projectRepository.replayDelete(mutation.localId)
        }
    }

    private suspend fun replayYarn(mutation: PendingMutationEntity) {
        when (mutation.operation) {
            MutationOperation.CREATE -> {
                val request = json.decodeFromString<YarnWriteRequest>(mutation.payloadJson!!)
                val realId = yarnRepository.replayCreate(mutation.localId, request)
                pendingMutationDao.reassignLocalId(
                    MutationEntityType.YARN,
                    mutation.localId,
                    realId,
                )
            }
            MutationOperation.UPDATE -> {
                val request = json.decodeFromString<YarnWriteRequest>(mutation.payloadJson!!)
                yarnRepository.replayUpdate(mutation.localId, request)
            }
            MutationOperation.DELETE -> yarnRepository.replayDelete(mutation.localId)
        }
    }
}
