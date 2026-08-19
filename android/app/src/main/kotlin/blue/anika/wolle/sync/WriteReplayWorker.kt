package blue.anika.wolle.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import blue.anika.wolle.data.api.dto.ProjectDto
import blue.anika.wolle.data.api.dto.ProjectWriteRequest
import blue.anika.wolle.data.api.dto.YarnDto
import blue.anika.wolle.data.api.dto.YarnWriteRequest
import blue.anika.wolle.data.db.dao.PendingMutationDao
import blue.anika.wolle.data.db.entity.MutationEntityType
import blue.anika.wolle.data.db.entity.MutationOperation
import blue.anika.wolle.data.db.entity.PendingMutationEntity
import blue.anika.wolle.data.repository.ProjectRepository
import blue.anika.wolle.data.repository.YarnRepository
import blue.anika.wolle.data.uploads.PendingAttachmentDelete
import blue.anika.wolle.data.uploads.PendingUpload
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import retrofit2.HttpException
import timber.log.Timber

/**
 * Flushes `PendingMutationDao`'s outbox (SNA-8) once connectivity returns - see
 * `ProjectRepository.createProject`/`updateProject`/`deleteProject` (and the yarn equivalents) for
 * where mutations get queued. Replays strictly in insertion order (`getAll()` is `ORDER BY id ASC`)
 * since an UPDATE/DELETE queued against a still-unsynced CREATE's temp id depends on that CREATE
 * having replayed first to learn the real server id.
 *
 * A *transient* replay failure (still offline, a real server error) leaves the mutation queued with
 * its error message recorded (`lastErrorMessage`, surfaced as a "Sync issue" by the UI) and retries
 * on the next pass. A *conflict* (SNA-33: the target was edited or deleted elsewhere in the
 * meantime, detected via the update endpoints' 409/404) is different - retrying it would just get
 * the same rejection forever, so [ConflictException] short-circuits that: the local cache adopts
 * whatever the server says is current, and the mutation is marked resolved (`markConflict`) rather
 * than retried, while still surfacing what happened via the same `lastErrorMessage` mechanism.
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
        // Re-read each row after the previous mutation. A CREATE can reassign the local temp id on
        // all later mutations, so replaying the initial snapshot would still target the old id.
        for (queuedMutation in pendingMutationDao.getAll()) {
            val mutation = pendingMutationDao.getById(queuedMutation.id) ?: continue
            try {
                replay(mutation)
                pendingMutationDao.delete(mutation.id)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (conflict: ConflictException) {
                Timber.i(
                    "Resolved conflict for mutation ${mutation.id} (${mutation.entityType}/${mutation.operation}): ${conflict.message}"
                )
                pendingMutationDao.markConflict(mutation.id, conflict.message!!)
                // Not anyFailure: this mutation is done (resolved, not pending) - nothing to retry.
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
            else -> error("Unknown mutation entity type: ${mutation.entityType}")
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
                try {
                    projectRepository.replayUpdate(mutation.localId, request)
                } catch (e: HttpException) {
                    when (e.code()) {
                        404 -> {
                            projectRepository.dropDeletedProject(mutation.localId)
                            throw ConflictException(
                                "This project was deleted on another device - your change wasn't applied."
                            )
                        }
                        409 -> {
                            decodeConflictBody<ProjectDto>(e)?.let {
                                projectRepository.adoptRemoteProject(it)
                            }
                            throw ConflictException(
                                "This project was edited elsewhere - your change was discarded and the latest version is now shown."
                            )
                        }
                        else -> throw e
                    }
                }
            }
            MutationOperation.DELETE -> projectRepository.replayDelete(mutation.localId)
            MutationOperation.PROJECT_TITLE_IMAGE_UPLOAD -> {
                val upload = json.decodeFromString<PendingUpload>(mutation.payloadJson!!)
                projectRepository.replayTitleImageUpload(mutation.localId, upload)
            }
            MutationOperation.PROJECT_STEP_IMAGE_UPLOAD -> {
                val upload = json.decodeFromString<PendingUpload>(mutation.payloadJson!!)
                projectRepository.replayStepImageUpload(mutation.localId, upload)
            }
            MutationOperation.PROJECT_ATTACHMENT_UPLOAD -> {
                val upload = json.decodeFromString<PendingUpload>(mutation.payloadJson!!)
                projectRepository.replayAttachmentUpload(mutation.localId, upload)
            }
            MutationOperation.PROJECT_ATTACHMENT_DELETE -> {
                val deletion =
                    json.decodeFromString<PendingAttachmentDelete>(mutation.payloadJson!!)
                projectRepository.replayAttachmentDelete(mutation.localId, deletion.attachmentId)
            }
            else -> error("Unknown project mutation operation: ${mutation.operation}")
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
                try {
                    yarnRepository.replayUpdate(mutation.localId, request)
                } catch (e: HttpException) {
                    when (e.code()) {
                        404 -> {
                            yarnRepository.dropDeletedYarn(mutation.localId)
                            throw ConflictException(
                                "This yarn was deleted on another device - your change wasn't applied."
                            )
                        }
                        409 -> {
                            decodeConflictBody<YarnDto>(e)?.let {
                                yarnRepository.adoptRemoteYarn(it)
                            }
                            throw ConflictException(
                                "This yarn was edited elsewhere - your change was discarded and the latest version is now shown."
                            )
                        }
                        else -> throw e
                    }
                }
            }
            MutationOperation.DELETE -> yarnRepository.replayDelete(mutation.localId)
            MutationOperation.YARN_PHOTO_UPLOAD -> {
                val upload = json.decodeFromString<PendingUpload>(mutation.payloadJson!!)
                yarnRepository.replayPhotoUpload(mutation.localId, upload)
            }
            else -> error("Unknown yarn mutation operation: ${mutation.operation}")
        }
    }

    private inline fun <reified T> decodeConflictBody(exception: HttpException): T? {
        val body = exception.response()?.errorBody()?.string() ?: return null
        return parseConflictDetail(json, body)
    }
}

/**
 * FastAPI wraps a 409's body as `{"detail": <the entity's current server state>}` (SNA-33) - decode
 * that inner object, tolerating a missing/malformed body (network proxies, older server versions)
 * by returning null rather than failing the whole conflict-resolution path. A top-level function
 * (not a class member) so it's unit-testable against a raw JSON string, without needing to mock a
 * Retrofit `HttpException`.
 */
internal inline fun <reified T> parseConflictDetail(json: Json, errorBody: String): T? =
    runCatching {
        val detail = json.parseToJsonElement(errorBody).jsonObject["detail"] ?: return null
        json.decodeFromJsonElement<T>(detail)
    }
    .getOrNull()

/** Thrown when a replay hits a resolved SNA-33 conflict - see [WriteReplayWorker]'s kdoc. */
private class ConflictException(message: String) : Exception(message)
