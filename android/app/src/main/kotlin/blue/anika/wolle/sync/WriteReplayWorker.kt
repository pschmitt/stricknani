package blue.anika.wolle.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import blue.anika.wolle.data.api.dto.ProjectDto
import blue.anika.wolle.data.api.dto.ProjectWriteRequest
import blue.anika.wolle.data.api.dto.YarnDto
import blue.anika.wolle.data.api.dto.YarnWriteRequest
import blue.anika.wolle.data.api.v2.ProjectV2Dto
import blue.anika.wolle.data.api.v2.YarnV2Dto
import blue.anika.wolle.data.api.v2.parseV2Conflict
import blue.anika.wolle.data.api.v2.toV1
import blue.anika.wolle.data.db.dao.PendingMutationDao
import blue.anika.wolle.data.db.entity.MutationEntityType
import blue.anika.wolle.data.db.entity.MutationOperation
import blue.anika.wolle.data.db.entity.PendingMutationEntity
import blue.anika.wolle.data.errorreporting.ErrorReportingController
import blue.anika.wolle.data.errorreporting.SyncFailureClass
import blue.anika.wolle.data.errorreporting.SyncFailureClassifier
import blue.anika.wolle.data.errorreporting.SyncFailureKind
import blue.anika.wolle.data.errorreporting.SyncFailureReport
import blue.anika.wolle.data.errorreporting.SyncFailureReportSanitizer
import blue.anika.wolle.data.repository.ProjectRepository
import blue.anika.wolle.data.repository.QueuedRestore
import blue.anika.wolle.data.repository.TrashException
import blue.anika.wolle.data.repository.TrashFailure
import blue.anika.wolle.data.repository.TrashRef
import blue.anika.wolle.data.repository.TrashRepository
import blue.anika.wolle.data.repository.YarnRepository
import blue.anika.wolle.data.settings.SettingsRepository
import blue.anika.wolle.data.uploads.PendingAttachmentDelete
import blue.anika.wolle.data.uploads.PendingGalleryImageDelete
import blue.anika.wolle.data.uploads.PendingMediaOrder
import blue.anika.wolle.data.uploads.PendingUpload
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import retrofit2.HttpException

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
    private val trashRepository: TrashRepository,
    private val json: Json,
    private val settingsRepository: SettingsRepository,
    private val errorReportingController: ErrorReportingController,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // An expired OIDC sign-in would only 401 each write. They stay queued untouched (not
        // failed, not dropped) and replay after the user signs in again.
        if (settingsRepository.sessionExpired.value) return Result.success()
        if (
            shouldSkipBackgroundWork(
                background = inputData.getBoolean(SyncWorker.KEY_BACKGROUND, false),
                powerSaveMode = applicationContext.isPowerSaveMode(),
            )
        ) {
            return Result.success()
        }
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
                pendingMutationDao.markConflict(mutation.id, conflict.message!!)
                reportHandledFailure(
                    mutation,
                    SyncFailureClass.CONFLICT,
                    conflict.httpStatus,
                    conflict.stackTrace.toList(),
                )
                // Not anyFailure: this mutation is done (resolved, not pending) - nothing to retry.
            } catch (forbidden: HttpException) {
                if (forbidden.code() != 403) {
                    handleReplayFailure(mutation, forbidden)
                    anyFailure = true
                    break
                }
                // A queued edit of somebody else's item, replayed after the role was lowered or
                // withdrawn (an editor became a viewer, an administrator lost the role): resolved
                // as a visible conflict like any other rejected write, so it neither blocks the
                // queue nor is retried. The draft stays in the conflict record; the cache adopts
                // the server's copy (or drops the row when access ended entirely). Nothing is
                // re-shared or re-linked on the user's behalf.
                pendingMutationDao.markConflict(mutation.id, NOT_ALLOWED_MESSAGE)
                reportHandledFailure(
                    mutation,
                    SyncFailureClass.FORBIDDEN,
                    403,
                    forbidden.stackTrace.toList(),
                )
                adoptServerCopy(mutation)
            } catch (failure: Exception) {
                handleReplayFailure(mutation, failure)
                anyFailure = true
                // Later mutations may depend on this one (most notably an UPDATE/DELETE or media
                // upload following an offline CREATE). Keep insertion order meaningful by leaving
                // the remainder untouched for the next retry rather than sending them with a
                // stale temporary id.
                break
            }
        }
        return if (anyFailure) Result.retry() else Result.success()
    }

    private suspend fun adoptServerCopy(mutation: PendingMutationEntity) {
        if (mutation.localId <= 0) return
        when (mutation.entityType) {
            MutationEntityType.PROJECT -> projectRepository.adoptServerCopy(mutation.localId)
            MutationEntityType.YARN -> yarnRepository.adoptServerCopy(mutation.localId)
        }
    }

    private suspend fun handleReplayFailure(mutation: PendingMutationEntity, failure: Exception) {
        pendingMutationDao.setError(mutation.id, failure.message ?: "Sync failed")
        reportHandledFailure(
            mutation,
            SyncFailureClassifier.classify(failure),
            (failure as? HttpException)?.code() ?: 0,
            failure.stackTrace.toList(),
        )
    }

    private suspend fun reportHandledFailure(
        mutation: PendingMutationEntity,
        failureClass: SyncFailureClass,
        httpStatus: Int,
        stackTrace: List<StackTraceElement> = emptyList(),
    ) {
        val safeReport =
            SyncFailureReportSanitizer.sanitize(
                SyncFailureReport(
                    kind = SyncFailureKind.QUEUED_WRITE,
                    operation =
                        if (mutation.operation.startsWith("${mutation.entityType}_"))
                            mutation.operation
                        else "${mutation.entityType}_${mutation.operation}",
                    failureClass = failureClass,
                    httpStatus = httpStatus,
                    stackTrace = stackTrace,
                )
            )
        Log.w(
            "SyncReplay",
            "Queued write handled: operation=${safeReport.operation}, " +
                "cause=${safeReport.failureClass.tagValue}, http=${safeReport.httpStatus}",
        )
        errorReportingController.reportSyncFailure(safeReport)
    }

    private suspend fun replay(mutation: PendingMutationEntity) {
        when (mutation.entityType) {
            MutationEntityType.PROJECT -> replayProject(mutation)
            MutationEntityType.YARN -> replayYarn(mutation)
            MutationEntityType.TRASH -> replayTrash(mutation)
            else -> error("Unknown mutation entity type: ${mutation.entityType}")
        }
    }

    /** An Undo tapped while the delete had already been sent and the device was offline. */
    private suspend fun replayTrash(mutation: PendingMutationEntity) {
        when (mutation.operation) {
            MutationOperation.TRASH_RESTORE -> {
                val restore = json.decodeFromString<QueuedRestore>(mutation.payloadJson!!)
                try {
                    trashRepository.restore(TrashRef(restore.kind, restore.id))
                } catch (failure: TrashException) {
                    when (failure.failure) {
                        // Restored elsewhere, or deleted for good since: nothing left to do.
                        TrashFailure.NotFound -> Unit
                        TrashFailure.ParentInTrash ->
                            throw ConflictException(PARENT_IN_TRASH_MESSAGE, 409)
                        TrashFailure.NameTaken -> throw ConflictException(NAME_TAKEN_MESSAGE, 409)
                        TrashFailure.NotAllowed -> throw ConflictException(NOT_ALLOWED_MESSAGE, 403)
                        else -> throw failure
                    }
                }
            }
            else -> error("Unknown trash mutation operation: ${mutation.operation}")
        }
    }

    private suspend fun replayProject(mutation: PendingMutationEntity) {
        when (mutation.operation) {
            MutationOperation.CREATE -> {
                val request = json.decodeFromString<ProjectWriteRequest>(mutation.payloadJson!!)
                projectRepository.replayCreate(mutation.localId, request)
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
                                "This project is gone or no longer shared with you - your change wasn't applied.",
                                404,
                            )
                        }
                        409 -> {
                            parseProjectConflict(json, e.errorBodyText())?.let {
                                projectRepository.adoptRemoteProject(it)
                            }
                            throw ConflictException(
                                "This project was edited elsewhere - your change was discarded and the latest version is now shown.",
                                409,
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
            MutationOperation.PROJECT_IMAGE_DELETE -> {
                val deletion =
                    json.decodeFromString<PendingGalleryImageDelete>(mutation.payloadJson!!)
                projectRepository.replayImageDelete(mutation.localId, deletion.imageId)
            }
            MutationOperation.PROJECT_TITLE_IMAGE_ORDER -> {
                val order = json.decodeFromString<PendingMediaOrder>(mutation.payloadJson!!)
                projectRepository.replayTitleImageOrder(mutation.localId, order)
            }
            MutationOperation.PROJECT_STEP_IMAGE_ORDER -> {
                val order = json.decodeFromString<PendingMediaOrder>(mutation.payloadJson!!)
                projectRepository.replayStepImageOrder(mutation.localId, order)
            }
            else -> error("Unknown project mutation operation: ${mutation.operation}")
        }
    }

    private suspend fun replayYarn(mutation: PendingMutationEntity) {
        when (mutation.operation) {
            MutationOperation.CREATE -> {
                val request = json.decodeFromString<YarnWriteRequest>(mutation.payloadJson!!)
                yarnRepository.replayCreate(mutation.localId, request)
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
                                "This yarn is gone or no longer shared with you - your change wasn't applied.",
                                404,
                            )
                        }
                        409 -> {
                            parseYarnConflict(json, e.errorBodyText())?.let {
                                yarnRepository.adoptRemoteYarn(it)
                            }
                            throw ConflictException(
                                "This yarn was edited elsewhere - your change was discarded and the latest version is now shown.",
                                409,
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
            MutationOperation.YARN_PHOTO_DELETE -> {
                val deletion =
                    json.decodeFromString<PendingGalleryImageDelete>(mutation.payloadJson!!)
                yarnRepository.replayPhotoDelete(mutation.localId, deletion.imageId)
            }
            MutationOperation.YARN_PHOTO_ORDER -> {
                val order = json.decodeFromString<PendingMediaOrder>(mutation.payloadJson!!)
                yarnRepository.replayPhotoOrder(mutation.localId, order)
            }
            else -> error("Unknown yarn mutation operation: ${mutation.operation}")
        }
    }
}

private const val NOT_ALLOWED_MESSAGE =
    "Your access to this item changed - your change was not saved. Your draft is kept here."

private const val PARENT_IN_TRASH_MESSAGE =
    "The project or yarn is still in the trash - restore it first, then restore this item from the Trash screen."

private const val NAME_TAKEN_MESSAGE =
    "A category with this name exists again - rename one of them, then restore it from the Trash screen."

private fun HttpException.errorBodyText(): String = response()?.errorBody()?.string().orEmpty()

/**
 * A 409's current server state on either API generation: v2 sends `{error:{code:"conflict",
 * current:<entity>}}` (the entity carries the extra `*_html` fields), v1 sends `{detail:<entity>}`.
 * The two shapes never overlap, so each parser returns null for the other's body.
 */
internal fun parseProjectConflict(json: Json, errorBody: String): ProjectDto? =
    parseV2Conflict<ProjectV2Dto>(json, errorBody)?.toV1()
        ?: parseConflictDetail<ProjectDto>(json, errorBody)

internal fun parseYarnConflict(json: Json, errorBody: String): YarnDto? =
    parseV2Conflict<YarnV2Dto>(json, errorBody)?.toV1()
        ?: parseConflictDetail<YarnDto>(json, errorBody)

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
private class ConflictException(message: String, val httpStatus: Int) : Exception(message)
