package blue.anika.wolle.data.repository

import androidx.room.withTransaction
import blue.anika.wolle.data.api.ProjectsApi
import blue.anika.wolle.data.api.SyncApi
import blue.anika.wolle.data.api.dto.ProjectDto
import blue.anika.wolle.data.api.dto.ProjectWriteRequest
import blue.anika.wolle.data.api.dto.StepDto
import blue.anika.wolle.data.db.AppDatabase
import blue.anika.wolle.data.db.dao.PendingMutationDao
import blue.anika.wolle.data.db.dao.ProjectDao
import blue.anika.wolle.data.db.dao.SyncStateDao
import blue.anika.wolle.data.db.entity.MutationEntityType
import blue.anika.wolle.data.db.entity.MutationOperation
import blue.anika.wolle.data.db.entity.PendingMutationEntity
import blue.anika.wolle.data.db.entity.ProjectEntity
import blue.anika.wolle.data.db.entity.SyncStateEntity
import blue.anika.wolle.data.uploads.PendingAttachmentDelete
import blue.anika.wolle.data.uploads.PendingUpload
import blue.anika.wolle.data.uploads.PendingUploadStore
import blue.anika.wolle.data.util.DateTimeUtils
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException

private const val ENTITY_TYPE = "project"

data class ProjectRefreshResult(val detail: ProjectDto, val changed: Boolean)

/** Cache-first access to projects, delta-synced against `/api/v1/sync/projects`. */
@Singleton
class ProjectRepository
@Inject
constructor(
    private val database: AppDatabase,
    private val projectDao: ProjectDao,
    private val syncStateDao: SyncStateDao,
    private val syncApi: SyncApi,
    private val projectsApi: ProjectsApi,
    private val pendingMutationDao: PendingMutationDao,
    private val pendingUploadStore: PendingUploadStore,
    private val json: Json,
) {
    fun observeAll(): Flow<List<ProjectEntity>> = projectDao.observeAll()

    fun observeById(id: Int): Flow<ProjectEntity?> = projectDao.observeById(id)

    fun decodeDetail(entity: ProjectEntity): ProjectDto = json.decodeFromString(entity.detailJson)

    fun decodeTags(entity: ProjectEntity): List<String> = json.decodeFromString(entity.tagsJson)

    /** Refreshes one project and returns its detail so a detail screen can refresh linked yarns. */
    suspend fun refreshOne(id: Int): ProjectRefreshResult {
        val detail = projectsApi.getProject(id)
        val refreshed = detail.toEntity(json)
        val changed = projectDao.getById(id)?.let { it != refreshed } ?: true
        projectDao.upsertAll(listOf(refreshed))
        return ProjectRefreshResult(detail = detail, changed = changed)
    }

    /**
     * Flips the favorite flag immediately in Room (optimistic), then fires the network call; on
     * failure the row is reverted to `wasFavorite` rather than left showing a lie. Direct online
     * call, not queued - see `ProjectsApi`'s kdoc for why favorites skip SNA-8.
     */
    suspend fun toggleFavorite(entity: ProjectEntity, wasFavorite: Boolean) {
        projectDao.upsertAll(listOf(entity.copy(isFavorite = !wasFavorite)))
        try {
            val updated =
                if (wasFavorite) projectsApi.unfavoriteProject(entity.id)
                else projectsApi.favoriteProject(entity.id)
            projectDao.upsertAll(listOf(updated.toEntity(json)))
        } catch (e: Exception) {
            projectDao.upsertAll(listOf(entity.copy(isFavorite = wasFavorite)))
            throw e
        }
    }

    /**
     * Returns whether this sync actually pulled any change - see SyncWorker/SyncNotifier (SNA-14).
     */
    suspend fun sync(): Boolean {
        val cursor = syncStateDao.getCursor(ENTITY_TYPE)
        val response = syncApi.syncProjects(since = cursor)
        val pendingUpdatedIds =
            response.updated
                .map { it.id }
                .takeIf { it.isNotEmpty() }
                ?.let { pendingMutationDao.getReplayableLocalIds(ENTITY_TYPE, it).toSet() }
                ?: emptySet()
        val pendingDeletedIds =
            response.deletedIds
                .takeIf { it.isNotEmpty() }
                ?.let { pendingMutationDao.getReplayableLocalIds(ENTITY_TYPE, it).toSet() }
                ?: emptySet()
        response.updated
            .filterNot { it.id in pendingUpdatedIds }
            .takeIf { it.isNotEmpty() }
            ?.let { projectDao.upsertAll(it.map { project -> project.toEntity(json) }) }
        response.deletedIds.filterNot { it in pendingDeletedIds }.takeIf { it.isNotEmpty() }?.let {
            projectDao.deleteByIds(it)
        }
        syncStateDao.setCursor(SyncStateEntity(ENTITY_TYPE, response.serverTime))
        return response.updated.isNotEmpty() || response.deletedIds.isNotEmpty()
    }

    /**
     * Writes a placeholder row to Room immediately (a negative client-generated temp id, since the
     * real id doesn't exist until the queued create actually reaches the server) and queues a
     * [MutationOperation.CREATE] for `sync/WriteReplayWorker.kt` to replay. Works with zero
     * connectivity - the row shows up in every screen right away.
     */
    suspend fun createProject(request: ProjectWriteRequest): Int {
        return database.withTransaction {
            val tempId = minOf(projectDao.minId() ?: 0, 0) - 1
            projectDao.upsertAll(listOf(request.toPlaceholderEntity(tempId, json)))
            pendingMutationDao.insert(
                PendingMutationEntity(
                    entityType = MutationEntityType.PROJECT,
                    operation = MutationOperation.CREATE,
                    localId = tempId,
                    payloadJson = json.encodeToString(request),
                    createdAt = System.currentTimeMillis(),
                )
            )
            tempId
        }
    }

    /**
     * Applies the edit to the local row immediately and queues an [MutationOperation.UPDATE],
     * stamped with the `updatedAt` this edit was based on (SNA-33) - `WriteReplayWorker` sends it
     * as `expected_updated_at` so the server can detect (and reject with 409) a write that's about
     * to clobber a newer edit that landed elsewhere in the meantime.
     */
    suspend fun updateProject(id: Int, request: ProjectWriteRequest) {
        database.withTransaction {
            val existing = projectDao.getById(id) ?: return@withTransaction
            projectDao.upsertAll(listOf(request.applyTo(existing, json)))
            pendingMutationDao.deleteResolvedConflicts(MutationEntityType.PROJECT, id)
            val queued =
                pendingMutationDao.getReplayable(
                    MutationEntityType.PROJECT,
                    MutationOperation.UPDATE,
                    id,
                )
            val payload =
                json.encodeToString(
                    request
                        .withExpectedUpdatedAt(id, existing.detailJson, json)
                        .coalesceExpectedUpdatedAt(queued?.payloadJson, json)
                )
            if (queued == null) {
                pendingMutationDao.insert(
                    PendingMutationEntity(
                        entityType = MutationEntityType.PROJECT,
                        operation = MutationOperation.UPDATE,
                        localId = id,
                        payloadJson = payload,
                        createdAt = System.currentTimeMillis(),
                    )
                )
            } else {
                pendingMutationDao.replacePayload(queued.id, payload)
            }
        }
    }

    /** Removes the local row immediately and queues a [MutationOperation.DELETE]. */
    suspend fun deleteProject(id: Int) {
        database.withTransaction {
            projectDao.deleteByIds(listOf(id))
            pendingMutationDao.deleteResolvedConflicts(MutationEntityType.PROJECT, id)
            pendingMutationDao.insert(
                PendingMutationEntity(
                    entityType = MutationEntityType.PROJECT,
                    operation = MutationOperation.DELETE,
                    localId = id,
                    payloadJson = null,
                    createdAt = System.currentTimeMillis(),
                )
            )
        }
    }

    suspend fun queueTitleImageUpload(projectId: Int, upload: PendingUpload) {
        database.withTransaction {
            pendingMutationDao.insert(
                PendingMutationEntity(
                    entityType = MutationEntityType.PROJECT,
                    operation = MutationOperation.PROJECT_TITLE_IMAGE_UPLOAD,
                    localId = projectId,
                    payloadJson = json.encodeToString(upload),
                    createdAt = System.currentTimeMillis(),
                )
            )
        }
    }

    suspend fun queueStepImageUpload(projectId: Int, upload: PendingUpload) {
        require(upload.stepIndex != null) { "A step image needs its step index" }
        database.withTransaction {
            pendingMutationDao.insert(
                PendingMutationEntity(
                    entityType = MutationEntityType.PROJECT,
                    operation = MutationOperation.PROJECT_STEP_IMAGE_UPLOAD,
                    localId = projectId,
                    payloadJson = json.encodeToString(upload),
                    createdAt = System.currentTimeMillis(),
                )
            )
        }
    }

    /** Queues a project attachment upload; the copied file survives picker/activity teardown. */
    suspend fun queueAttachmentUpload(projectId: Int, upload: PendingUpload) {
        database.withTransaction {
            pendingMutationDao.insert(
                PendingMutationEntity(
                    entityType = MutationEntityType.PROJECT,
                    operation = MutationOperation.PROJECT_ATTACHMENT_UPLOAD,
                    localId = projectId,
                    payloadJson = json.encodeToString(upload),
                    createdAt = System.currentTimeMillis(),
                )
            )
        }
    }

    /**
     * Removes an attachment from the cache immediately and queues its server-side deletion. The
     * mutation keeps the project id in [PendingMutationEntity.localId], so it is still retargeted
     * by the existing temp-id reassignment when a queued project create is replayed.
     */
    suspend fun queueAttachmentDelete(projectId: Int, attachmentId: Int) {
        database.withTransaction {
            projectDao.getById(projectId)?.let { existing ->
                val current = json.decodeFromString<ProjectDto>(existing.detailJson)
                val updated = removeProjectAttachment(current, attachmentId)
                if (updated != current) projectDao.upsertAll(listOf(updated.toEntity(json)))
            }
            pendingMutationDao.deleteResolvedConflicts(MutationEntityType.PROJECT, projectId)
            pendingMutationDao.insert(
                PendingMutationEntity(
                    entityType = MutationEntityType.PROJECT,
                    operation = MutationOperation.PROJECT_ATTACHMENT_DELETE,
                    localId = projectId,
                    payloadJson = json.encodeToString(PendingAttachmentDelete(attachmentId)),
                    createdAt = System.currentTimeMillis(),
                )
            )
        }
    }

    /** Called only by `WriteReplayWorker` once a queued create actually reaches the server. */
    suspend fun replayCreate(tempId: Int, request: ProjectWriteRequest): Int {
        val created = projectsApi.createProject(request)
        database.withTransaction {
            projectDao.deleteByIds(listOf(tempId))
            projectDao.upsertAll(listOf(created.toEntity(json)))
            pendingMutationDao.reassignLocalId(MutationEntityType.PROJECT, tempId, created.id)
        }
        return created.id
    }

    /** Called only by `WriteReplayWorker` - a 409 (SNA-33 conflict) propagates to its caller. */
    suspend fun replayUpdate(id: Int, request: ProjectWriteRequest) {
        val updated = projectsApi.updateProject(id, request)
        projectDao.upsertAll(listOf(updated.toEntity(json)))
    }

    /**
     * Called only by `WriteReplayWorker` when a queued update conflicted (409) with a newer
     * server-side edit (SNA-33) - adopts the server's current state, included in the 409 response
     * body, so the local cache reflects reality instead of staying stuck on the pre-conflict value.
     */
    suspend fun adoptRemoteProject(dto: ProjectDto) {
        projectDao.upsertAll(listOf(dto.toEntity(json)))
    }

    /**
     * Called only by `WriteReplayWorker` when a queued update's target was deleted server-side in
     * the meantime (404, SNA-33) - a regular `sync()` pass would normally have already removed the
     * local row, but this replay can race ahead of the next sync, so drop it explicitly too.
     */
    suspend fun dropDeletedProject(id: Int) {
        projectDao.deleteByIds(listOf(id))
    }

    /**
     * Called only by `WriteReplayWorker`. Idempotent: a 404 means the project is already gone
     * server-side (SNA-33) - exactly the caller's goal - so it's treated as success, not a failure
     * worth retrying.
     */
    suspend fun replayDelete(id: Int) {
        try {
            projectsApi.deleteProject(id)
        } catch (e: HttpException) {
            if (e.code() != 404) throw e
        }
    }

    /** Called only by [blue.anika.wolle.sync.WriteReplayWorker]. */
    suspend fun replayTitleImageUpload(id: Int, upload: PendingUpload) {
        projectsApi.uploadTitleImage(
            projectId = id,
            file = pendingUploadStore.multipart(upload),
            altText = upload.altText.toRequestBody("text/plain".toMediaType()),
        )
        pendingUploadStore.delete(upload)
        runCatching { refreshOne(id) }
    }

    /** Resolves the current server step by its editor position, then uploads its image. */
    suspend fun replayStepImageUpload(id: Int, upload: PendingUpload) {
        val stepIndex = requireNotNull(upload.stepIndex) { "A step image needs its step index" }
        val step =
            projectsApi.getProject(id).steps.getOrNull(stepIndex)
                ?: error("Project step $stepIndex no longer exists")
        projectsApi.uploadStepImage(
            projectId = id,
            stepId = step.id,
            file = pendingUploadStore.multipart(upload),
            altText = upload.altText.toRequestBody("text/plain".toMediaType()),
        )
        pendingUploadStore.delete(upload)
        runCatching { refreshOne(id) }
    }

    /** Called only by [blue.anika.wolle.sync.WriteReplayWorker]. */
    suspend fun replayAttachmentUpload(id: Int, upload: PendingUpload) {
        projectsApi.uploadAttachment(
            projectId = id,
            file = pendingUploadStore.multipart(upload),
        )
        pendingUploadStore.delete(upload)
        runCatching { refreshOne(id) }
    }

    /**
     * Attachment deletion is idempotent: a 404 means the attachment is already gone, which is the
     * desired end state for this queued mutation.
     */
    suspend fun replayAttachmentDelete(id: Int, attachmentId: Int) {
        try {
            projectsApi.deleteAttachment(projectId = id, attachmentId = attachmentId)
        } catch (e: HttpException) {
            if (e.code() != 404) throw e
        }
        runCatching { refreshOne(id) }
    }
}

/**
 * `ProjectResponse` (unlike `ProjectListItemResponse`) has no server-computed `preview_url` - the
 * title image's thumbnail is the client-side equivalent, falling back to the first image.
 */
private fun ProjectDto.toEntity(json: Json): ProjectEntity =
    ProjectEntity(
        id = id,
        name = name,
        category = category,
        tagsJson = json.encodeToString(tags),
        isFavorite = isFavorite,
        updatedAt = DateTimeUtils.parseToEpochMillis(updatedAt),
        previewUrl = (images.firstOrNull { it.isTitleImage } ?: images.firstOrNull())?.thumbnailUrl,
        detailJson = json.encodeToString(this),
    )

internal fun removeProjectAttachment(project: ProjectDto, attachmentId: Int): ProjectDto =
    project.copy(attachments = project.attachments.filterNot { it.id == attachmentId })

private fun nowIso(): String = OffsetDateTime.now(ZoneOffset.UTC).toString()

/** A not-yet-synced local row, shown immediately while the queued create is in flight. */
private fun ProjectWriteRequest.toPlaceholderEntity(tempId: Int, json: Json): ProjectEntity {
    val now = nowIso()
    return ProjectDto(
            id = tempId,
            name = name,
            category = category,
            yarn = null,
            needles = needles,
            stitchSample = stitchSample,
            description = description,
            notes = notes,
            otherMaterials = otherMaterials,
            tags = tags,
            link = link,
            linkArchive = null,
            isAiEnhanced = isAiEnhanced,
            isFavorite = false,
            createdAt = now,
            updatedAt = now,
            yarnIds = yarnIds,
            steps = stepsFromRequest(emptyList(), steps),
            images = emptyList(),
            attachments = emptyList(),
        )
        .toEntity(json)
}

/**
 * Applies edited fields onto the cached detail, preserving what the form doesn't touch (steps
 * /images/attachments/favorite state/the read-only `yarn` free-text field).
 */
private fun ProjectWriteRequest.applyTo(existing: ProjectEntity, json: Json): ProjectEntity {
    val current = json.decodeFromString<ProjectDto>(existing.detailJson)
    val existingSteps = current.steps
    val updated =
        current.copy(
            name = name,
            category = category,
            needles = needles,
            stitchSample = stitchSample,
            description = description,
            notes = notes,
            otherMaterials = otherMaterials,
            tags = tags,
            link = link,
            isAiEnhanced = isAiEnhanced,
            yarnIds = yarnIds,
            steps = stepsFromRequest(existingSteps, steps),
            updatedAt = nowIso(),
        )
    return updated.toEntity(json)
}

/**
 * Adds the optimistic-concurrency precondition used by a queued update.
 *
 * A negative id is a local placeholder for an offline create, so its timestamp was generated by the
 * client and can never match the server-created row. The create must replay first; the dependent
 * update must therefore be unconditional rather than becoming a false conflict.
 */
internal fun ProjectWriteRequest.withExpectedUpdatedAt(
    localId: Int,
    existingDetailJson: String,
    json: Json,
): ProjectWriteRequest =
    copy(
        expectedUpdatedAt =
            localId
                .takeIf { it > 0 }
                ?.let { json.decodeFromString<ProjectDto>(existingDetailJson).updatedAt }
    )

/** Keeps the original server snapshot when several local edits are coalesced before replay. */
internal fun ProjectWriteRequest.coalesceExpectedUpdatedAt(
    previousPayloadJson: String?,
    json: Json,
): ProjectWriteRequest =
    copy(
        expectedUpdatedAt =
            previousPayloadJson
                ?.let { payload ->
                    runCatching {
                            json.decodeFromString<ProjectWriteRequest>(payload).expectedUpdatedAt
                        }
                        .getOrNull()
                }
                ?: expectedUpdatedAt
    )

private fun stepsFromRequest(
    existing: List<StepDto>,
    requested: List<blue.anika.wolle.data.api.dto.StepWriteRequest>,
): List<StepDto> = requested.mapIndexed { index, step ->
    val existingStep = step.id?.let { id -> existing.firstOrNull { it.id == id } }
    StepDto(
        id = existingStep?.id ?: step.id ?: -(index + 1),
        title = step.title,
        description = step.description,
        stepNumber = step.stepNumber.takeIf { it > 0 } ?: index + 1,
    )
}
