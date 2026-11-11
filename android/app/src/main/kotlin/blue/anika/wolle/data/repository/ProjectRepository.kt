package blue.anika.wolle.data.repository

import android.util.Log
import androidx.room.withTransaction
import blue.anika.wolle.data.api.ApiCapabilities
import blue.anika.wolle.data.api.ProjectsApi
import blue.anika.wolle.data.api.SyncApi
import blue.anika.wolle.data.api.dto.ImageDto
import blue.anika.wolle.data.api.dto.ProjectDto
import blue.anika.wolle.data.api.dto.ProjectStatus
import blue.anika.wolle.data.api.dto.ProjectWriteRequest
import blue.anika.wolle.data.api.dto.StepDto
import blue.anika.wolle.data.api.v2.MediaOrderRequest
import blue.anika.wolle.data.api.v2.ProjectStatusRequest
import blue.anika.wolle.data.api.v2.ProjectsV2Api
import blue.anika.wolle.data.api.v2.SyncV2Api
import blue.anika.wolle.data.api.v2.runScopedV2Sync
import blue.anika.wolle.data.api.v2.staleAfterFullResync
import blue.anika.wolle.data.api.v2.toV1
import blue.anika.wolle.data.db.AppDatabase
import blue.anika.wolle.data.db.dao.PendingMutationDao
import blue.anika.wolle.data.db.dao.ProjectDao
import blue.anika.wolle.data.db.dao.SyncStateDao
import blue.anika.wolle.data.db.entity.MutationEntityType
import blue.anika.wolle.data.db.entity.MutationOperation
import blue.anika.wolle.data.db.entity.PendingMutationEntity
import blue.anika.wolle.data.db.entity.ProjectEntity
import blue.anika.wolle.data.db.entity.SyncStateEntity
import blue.anika.wolle.data.media.MediaCacheEvictor
import blue.anika.wolle.data.uploads.PendingAttachmentDelete
import blue.anika.wolle.data.uploads.PendingGalleryImageDelete
import blue.anika.wolle.data.uploads.PendingMediaOrder
import blue.anika.wolle.data.uploads.PendingUpload
import blue.anika.wolle.data.uploads.PendingUploadStore
import blue.anika.wolle.data.util.DateTimeUtils
import blue.anika.wolle.ui.common.DeleteConfirmation
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import timber.log.Timber

private const val ENTITY_TYPE = "project"

data class ProjectRefreshResult(val detail: ProjectDto, val changed: Boolean)

/**
 * Cache-first access to projects, delta-synced against `/api/v2/sync/projects` (v1 on servers
 * without v2).
 */
@Singleton
class ProjectRepository
@Inject
constructor(
    private val database: AppDatabase,
    private val projectDao: ProjectDao,
    private val syncStateDao: SyncStateDao,
    private val syncApi: SyncApi,
    private val syncV2Api: SyncV2Api,
    private val projectsApi: ProjectsApi,
    private val projectsV2Api: ProjectsV2Api,
    private val capabilities: ApiCapabilities,
    private val pendingMutationDao: PendingMutationDao,
    private val pendingUploadStore: PendingUploadStore,
    private val json: Json,
    private val everyoneScope: EveryoneScope,
    private val mediaCacheEvictor: MediaCacheEvictor,
) {
    /** What the lists show: an administrator on Mine sees only their own rows (cache untouched). */
    fun observeAll(): Flow<List<ProjectEntity>> =
        combine(projectDao.observeAll(), everyoneScope.listFilter) { rows, me ->
            rows.visibleTo(me, { it.ownerId }) { isSharedWithMe(it.accessRole, it.accessDerived) }
        }

    /** Every cached row regardless of the Mine filter, for detail screens resolving links by id. */
    fun observeAllIncludingForeign(): Flow<List<ProjectEntity>> = projectDao.observeAll()

    /**
     * Makes sure [id] is in Room before a detail screen reads it: fetches it when the cache misses
     * (see [loadIfMissing]). The row is cached whoever owns it; lists hide foreign rows on Mine
     * through [observeAll]'s filter, so it does not re-enter the Mine list.
     */
    suspend fun loadIfMissing(id: Int): ItemLoad =
        loadIfMissing(
            id = id,
            isCached = { projectDao.getById(id) != null },
            isDeletedLocally = {
                pendingMutationDao.getReplayable(
                    MutationEntityType.PROJECT,
                    MutationOperation.DELETE,
                    id,
                ) != null
            },
            fetchAndCache = { refreshOne(id) },
        )

    fun observeById(id: Int): Flow<ProjectEntity?> = projectDao.observeById(id)

    fun decodeDetail(entity: ProjectEntity): ProjectDto = json.decodeFromString(entity.detailJson)

    fun decodeTags(entity: ProjectEntity): List<String> = json.decodeFromString(entity.tagsJson)

    /** Refreshes one project and returns its detail so a detail screen can refresh linked yarns. */
    suspend fun refreshOne(id: Int): ProjectRefreshResult {
        val detail = fetchProject(id)
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
            val updated = setFavoriteRemote(entity.id, favorite = !wasFavorite)
            projectDao.upsertAll(listOf(updated.toEntity(json)))
        } catch (e: Exception) {
            projectDao.upsertAll(listOf(entity.copy(isFavorite = wasFavorite)))
            throw e
        }
    }

    /**
     * The quick "Mark as done" toggle: flips the status immediately in Room (optimistic), then
     * calls the server; on failure the row is reverted. Direct online call like favorites (the
     * write queue carries whole projects, a status flip is one field). Needs API v2.
     */
    suspend fun setStatus(entity: ProjectEntity, status: String) {
        val previous = entity.status
        projectDao.upsertAll(listOf(entity.copy(status = status)))
        try {
            check(capabilities.useV2()) { "Project status needs API v2" }
            val updated = projectsV2Api.setProjectStatus(entity.id, ProjectStatusRequest(status))
            projectDao.upsertAll(listOf(updated.data.toV1().toEntity(json)))
        } catch (e: Exception) {
            projectDao.upsertAll(listOf(entity.copy(status = previous)))
            throw e
        }
    }

    /** `GET` one project on the active API generation, mapped to the app's domain shape. */
    private suspend fun fetchProject(id: Int): ProjectDto =
        if (capabilities.useV2()) projectsV2Api.getProject(id).data.toV1()
        else projectsApi.getProject(id)

    /** v1: POST/DELETE; v2: PUT/DELETE on `.../favorite`. */
    private suspend fun setFavoriteRemote(id: Int, favorite: Boolean): ProjectDto =
        if (capabilities.useV2()) {
            val response =
                if (favorite) projectsV2Api.favoriteProject(id)
                else projectsV2Api.unfavoriteProject(id)
            response.data.toV1()
        } else if (favorite) projectsApi.favoriteProject(id) else projectsApi.unfavoriteProject(id)

    /**
     * Returns whether this sync actually pulled any change - see SyncWorker/SyncNotifier (SNA-14).
     */
    suspend fun sync(): Boolean = syncDetailed().changed

    /** The cached rows of [ids] (server ids), for naming what a sync changed. */
    suspend fun cachedByIds(ids: Collection<Int>): List<ProjectEntity> = ids.mapNotNull {
        projectDao.getById(it)
    }

    /** Same as [sync], but says which items changed (for the sync notification). */
    suspend fun syncDetailed(): ItemSyncResult = if (capabilities.useV2()) syncV2() else syncV1()

    private suspend fun syncV1(): ItemSyncResult {
        // A v1 server has no scope: it only ever serves the user's own items.
        val cursor = syncStateDao.getCursor(ENTITY_TYPE)
        val response = syncApi.syncProjects(since = cursor)
        val changed = applyDelta(response.updated, response.deletedIds)
        syncStateDao.setCursor(SyncStateEntity(ENTITY_TYPE, response.serverTime))
        return changed
    }

    /**
     * `GET /api/v2/sync/projects` with cursor paging and `full_resync_required` handling (see
     * [runV2Sync]). The delta semantics are those of [syncV1]: pending local writes win, tombstones
     * delete, and the new cursor is stored only after every page was applied.
     */
    private suspend fun syncV2(): ItemSyncResult {
        var changed = ItemSyncResult.None
        val scope = everyoneScope.forSync()
        val cursorKey = syncCursorKey(ENTITY_TYPE, scope)
        val outcome =
            runScopedV2Sync(
                since = syncStateDao.getCursor(cursorKey),
                idOf = { it.id },
                fetch = { since, cursor ->
                    syncV2Api.syncProjects(since = since, cursor = cursor, scope = scope.wire).data
                },
                onPage = { updated, deletedIds ->
                    changed += applyDelta(updated.map { it.toV1() }, deletedIds)
                },
            )
        if (outcome.fullResync) {
            val localIds = projectDao.getServerIds()
            val pending =
                localIds
                    .takeIf { it.isNotEmpty() }
                    ?.let { pendingMutationDao.getReplayableLocalIds(ENTITY_TYPE, it).toSet() }
                    ?: emptySet()
            val stale = staleAfterFullResync(localIds, outcome.seenIds, pending)
            if (stale.isNotEmpty()) {
                projectDao.deleteByIds(stale)
                changed += ItemSyncResult(deletedCount = stale.size)
            }
        }
        syncStateDao.setCursor(SyncStateEntity(cursorKey, outcome.serverTime))
        return changed
    }

    /** Applies one batch of server changes to Room; see [applyItemDelta]. */
    private suspend fun applyDelta(
        updated: List<ProjectDto>,
        deletedIds: List<Int>,
    ): ItemSyncResult =
        applyItemDeltaDetailed(
            updated = updated,
            deletedIds = deletedIds,
            idOf = { it.id },
            toEntity = { it.toEntity(json) },
            pendingIds = { ids ->
                pendingMutationDao.getReplayableLocalIds(ENTITY_TYPE, ids).toSet()
            },
            upsert = { projectDao.upsertAll(it) },
            load = { projectDao.getById(it) },
            delete = { projectDao.deleteByIds(it) },
            evictMedia = { row ->
                mediaCacheEvictor.evict(
                    json.decodeFromString<ProjectDto>(row.detailJson).mediaPaths() +
                        listOfNotNull(row.previewUrl)
                )
            },
        )

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
    suspend fun deleteProject(confirmed: DeleteConfirmation.Confirmed) {
        val id = confirmed.id
        // Traceable afterwards: only a confirmed delete dialog may get here.
        Log.w(
            DeleteAudit.TAG,
            DeleteAudit.queued(MutationEntityType.PROJECT, id, Throwable().stackTrace),
        )
        database.withTransaction {
            // Kept with the queued delete so that Undo can put the row back before it is sent.
            val snapshot = projectDao.getById(id)?.let { json.encodeToString(it) }
            projectDao.deleteByIds(listOf(id))
            pendingMutationDao.deleteResolvedConflicts(MutationEntityType.PROJECT, id)
            pendingMutationDao.insert(
                PendingMutationEntity(
                    entityType = MutationEntityType.PROJECT,
                    operation = MutationOperation.DELETE,
                    localId = id,
                    payloadJson = snapshot,
                    createdAt = System.currentTimeMillis(),
                )
            )
        }
    }

    /**
     * Undo of [deleteProject] while the delete is still in the write queue: drops the queued delete
     * and puts the cached row back. False when nothing could be cancelled (the delete was already
     * sent, or was queued without a snapshot); the caller then restores from the server's trash.
     */
    suspend fun undoQueuedDelete(id: Int): Boolean = database.withTransaction {
        val snapshot =
            QueuedDeleteUndo.cancelQueuedDelete(pendingMutationDao, MutationEntityType.PROJECT, id)
                ?: return@withTransaction false
        projectDao.upsertAll(listOf(json.decodeFromString<ProjectEntity>(snapshot)))
        true
    }

    /** Whether the delete of this row still waits in the write queue. */
    suspend fun hasQueuedDelete(id: Int): Boolean =
        QueuedDeleteUndo.hasQueuedDelete(pendingMutationDao, MutationEntityType.PROJECT, id)

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

    /** Removes a gallery image from the local detail immediately and queues its server deletion. */
    suspend fun queueImageDelete(projectId: Int, imageId: Int) {
        database.withTransaction {
            projectDao.getById(projectId)?.let { existing ->
                val current = json.decodeFromString<ProjectDto>(existing.detailJson)
                val updated = removeProjectImage(current, imageId)
                if (updated != current) projectDao.upsertAll(listOf(updated.toEntity(json)))
            }
            pendingMutationDao.deleteResolvedConflicts(MutationEntityType.PROJECT, projectId)
            pendingMutationDao.insert(
                PendingMutationEntity(
                    entityType = MutationEntityType.PROJECT,
                    operation = MutationOperation.PROJECT_IMAGE_DELETE,
                    localId = projectId,
                    payloadJson = json.encodeToString(PendingGalleryImageDelete(imageId)),
                    createdAt = System.currentTimeMillis(),
                )
            )
        }
    }

    /**
     * Applies a new title-image order to the cached detail at once and queues it for replay. A
     * second reorder before the first replayed replaces it, so only the latest order is sent.
     */
    suspend fun queueTitleImageOrder(projectId: Int, ids: List<Int>) {
        queueMediaOrder(
            projectId,
            MutationOperation.PROJECT_TITLE_IMAGE_ORDER,
            { detail -> PendingMediaOrder(detail?.completeTitleOrder(ids) ?: ids) },
        ) {
            it.withTitleImageOrder(it.completeTitleOrder(ids))
        }
    }

    /** Same as [queueTitleImageOrder] for one step's images. */
    suspend fun queueStepImageOrder(projectId: Int, stepId: Int, ids: List<Int>) {
        queueMediaOrder(
            projectId,
            MutationOperation.PROJECT_STEP_IMAGE_ORDER,
            { PendingMediaOrder(ids, stepId) },
        ) {
            it.withStepImageOrder(stepId, ids)
        }
    }

    private suspend fun queueMediaOrder(
        projectId: Int,
        operation: String,
        orderFor: (ProjectDto?) -> PendingMediaOrder,
        apply: (ProjectDto) -> ProjectDto,
    ) {
        database.withTransaction {
            val order =
                orderFor(
                    projectDao.getById(projectId)?.let {
                        json.decodeFromString<ProjectDto>(it.detailJson)
                    }
                )
            projectDao.getById(projectId)?.let { existing ->
                val current = json.decodeFromString<ProjectDto>(existing.detailJson)
                val updated = apply(current)
                if (updated != current) projectDao.upsertAll(listOf(updated.toEntity(json)))
            }
            pendingMutationDao.deleteResolvedConflicts(MutationEntityType.PROJECT, projectId)
            val queued =
                findQueuedOrder(
                    pendingMutationDao.getAllReplayable(
                        MutationEntityType.PROJECT,
                        operation,
                        projectId,
                    ),
                    order.stepId,
                    json,
                )
            val payload = json.encodeToString(order)
            if (queued == null) {
                pendingMutationDao.insert(
                    PendingMutationEntity(
                        entityType = MutationEntityType.PROJECT,
                        operation = operation,
                        localId = projectId,
                        payloadJson = payload,
                        createdAt = System.currentTimeMillis(),
                    )
                )
            } else {
                pendingMutationDao.replacePayload(queued.id, payload)
            }
        }
    }

    /** Called only by [blue.anika.wolle.sync.WriteReplayWorker]. See [replayMediaOrder]. */
    suspend fun replayTitleImageOrder(id: Int, order: PendingMediaOrder) =
        replayMediaOrder(id) { projectsV2Api.reorderTitleImages(id, MediaOrderRequest(order.ids)) }

    /** Called only by [blue.anika.wolle.sync.WriteReplayWorker]. See [replayMediaOrder]. */
    suspend fun replayStepImageOrder(id: Int, order: PendingMediaOrder) {
        val stepId = requireNotNull(order.stepId) { "A step image order needs its step id" }
        replayMediaOrder(id) {
            projectsV2Api.reorderStepImages(id, stepId, MediaOrderRequest(order.ids))
        }
    }

    /**
     * Sends a queued order. 404 (gone or wrong step), 405 (a server without the route) and 422 (the
     * group changed elsewhere, so the order no longer matches) are not worth retrying: the action
     * is dropped by returning normally, and the project is re-read so the cache shows the server's
     * order. Any other failure propagates and stays queued.
     */
    private suspend fun replayMediaOrder(id: Int, send: suspend () -> Unit) {
        val queuedUpdate =
            pendingMutationDao.getReplayable(
                MutationEntityType.PROJECT,
                MutationOperation.UPDATE,
                id,
            )
        val before = queuedUpdate?.let { fetchProjectOrNull(id) }
        if (!sendMediaOrder(send)) {
            Timber.i("Dropped queued media order for project $id")
            fetchProjectOrNull(id)?.let { projectDao.upsertAll(listOf(it.toEntity(json))) }
            return
        }

        val after = queuedUpdate?.let { fetchProjectOrNull(id) }
        if (after != null) {
            if (before != null) rebaseQueuedUpdateAfterMediaOrder(id, before, after)
            projectDao.upsertAll(listOf(after.toEntity(json)))
        } else {
            // The order has already been sent. A failed optional read must not keep it queued;
            // retain the update's original timestamp so a genuine conflict is still detected.
            fetchProjectOrNull(id)?.let { projectDao.upsertAll(listOf(it.toEntity(json))) }
        }
    }

    private suspend fun fetchProjectOrNull(id: Int): ProjectDto? =
        try {
            fetchProject(id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    private suspend fun rebaseQueuedUpdateAfterMediaOrder(
        id: Int,
        before: ProjectDto,
        after: ProjectDto,
    ) {
        database.withTransaction {
            val latest =
                pendingMutationDao.getReplayable(
                    MutationEntityType.PROJECT,
                    MutationOperation.UPDATE,
                    id,
                ) ?: return@withTransaction
            val request =
                try {
                    json.decodeFromString<ProjectWriteRequest>(latest.payloadJson!!)
                } catch (_: Exception) {
                    return@withTransaction
                }
            val rebased =
                request.rebaseExpectedUpdatedAtAfterMediaOrder(before, after)
                    ?: return@withTransaction
            pendingMutationDao.replacePayload(
                latest.id,
                json.encodeToString(rebased),
            )
        }
    }

    /**
     * The caller left (or otherwise lost) this item: drops the local row and its cached media at
     * once, as the sync tombstone would. Queued writes are kept; their replay learns the 404.
     */
    suspend fun forgetLocally(id: Int) {
        projectDao.getById(id)?.let { row ->
            runCatching {
                mediaCacheEvictor.evict(
                    json.decodeFromString<ProjectDto>(row.detailJson).mediaPaths() +
                        listOfNotNull(row.previewUrl)
                )
            }
        }
        projectDao.deleteByIds(listOf(id))
    }

    /**
     * After a refused write (403, role lowered): shows the server's copy instead of the refused
     * edit. A 404 means access ended entirely, so the local row goes; anything else (offline) is
     * left to the next sync.
     */
    suspend fun adoptServerCopy(id: Int) {
        try {
            refreshOne(id)
        } catch (e: HttpException) {
            if (e.code() == 404) projectDao.deleteByIds(listOf(id))
        } catch (e: java.io.IOException) {
            Timber.i("Could not refresh project $id after a refused write")
        }
    }

    /** Called only by `WriteReplayWorker` once a queued create actually reaches the server. */
    suspend fun replayCreate(tempId: Int, request: ProjectWriteRequest): Int {
        val created =
            if (capabilities.useV2()) projectsV2Api.createProject(request).data.toV1()
            else projectsApi.createProject(request)
        database.withTransaction {
            projectDao.deleteByIds(listOf(tempId))
            projectDao.upsertAll(listOf(created.toEntity(json)))
            pendingMutationDao.reassignLocalId(MutationEntityType.PROJECT, tempId, created.id)
        }
        return created.id
    }

    /** Called only by `WriteReplayWorker` - a 409 (SNA-33 conflict) propagates to its caller. */
    suspend fun replayUpdate(id: Int, request: ProjectWriteRequest) {
        val updated =
            if (capabilities.useV2()) projectsV2Api.updateProject(id, request).data.toV1()
            else projectsApi.updateProject(id, request)
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
    suspend fun replayDelete(id: Int, queuedAtMillis: Long = System.currentTimeMillis()) {
        Log.w(
            DeleteAudit.TAG,
            DeleteAudit.sending(
                MutationEntityType.PROJECT,
                id,
                queuedAtMillis,
                System.currentTimeMillis(),
            ),
        )
        try {
            if (capabilities.useV2()) projectsV2Api.deleteProject(id)
            else projectsApi.deleteProject(id)
        } catch (e: HttpException) {
            if (e.code() != 404) throw e
        }
    }

    /**
     * Uploads a project photo right away (the editor needs the saved image to offer Edit on it) and
     * returns it. Throws when the server cannot be reached; the caller then queues the upload. The
     * cached row is only refreshed when no local edit of it waits to be replayed.
     */
    suspend fun uploadTitleImageNow(id: Int, upload: PendingUpload): ImageDto {
        val file = pendingUploadStore.multipart(upload)
        val altText = upload.altText.toRequestBody("text/plain".toMediaType())
        val image =
            if (capabilities.useV2()) projectsV2Api.uploadTitleImage(id, file, altText).data
            else projectsApi.uploadTitleImage(projectId = id, file = file, altText = altText)
        pendingUploadStore.delete(upload)
        if (
            pendingMutationDao
                .getReplayableLocalIds(MutationEntityType.PROJECT, listOf(id))
                .isEmpty()
        )
            runCatching { refreshOne(id) }
        return image
    }

    /** Called only by [blue.anika.wolle.sync.WriteReplayWorker]. */
    suspend fun replayTitleImageUpload(id: Int, upload: PendingUpload) {
        val file = pendingUploadStore.multipart(upload)
        val altText = upload.altText.toRequestBody("text/plain".toMediaType())
        if (capabilities.useV2()) projectsV2Api.uploadTitleImage(id, file, altText)
        else projectsApi.uploadTitleImage(projectId = id, file = file, altText = altText)
        pendingUploadStore.delete(upload)
        runCatching { refreshOne(id) }
    }

    /** Resolves the current server step by its editor position, then uploads its image. */
    suspend fun replayStepImageUpload(id: Int, upload: PendingUpload) {
        val stepIndex = requireNotNull(upload.stepIndex) { "A step image needs its step index" }
        val step =
            fetchProject(id).steps.getOrNull(stepIndex)
                ?: error("Project step $stepIndex no longer exists")
        val file = pendingUploadStore.multipart(upload)
        val altText = upload.altText.toRequestBody("text/plain".toMediaType())
        if (capabilities.useV2()) projectsV2Api.uploadStepImage(id, step.id, file, altText)
        else
            projectsApi.uploadStepImage(
                projectId = id,
                stepId = step.id,
                file = file,
                altText = altText,
            )
        pendingUploadStore.delete(upload)
        runCatching { refreshOne(id) }
    }

    /** Called only by [blue.anika.wolle.sync.WriteReplayWorker]. */
    suspend fun replayAttachmentUpload(id: Int, upload: PendingUpload) {
        val file = pendingUploadStore.multipart(upload)
        if (capabilities.useV2()) projectsV2Api.uploadAttachment(id, file)
        else projectsApi.uploadAttachment(projectId = id, file = file)
        pendingUploadStore.delete(upload)
        runCatching { refreshOne(id) }
    }

    /**
     * Attachment deletion is idempotent: a 404 means the attachment is already gone, which is the
     * desired end state for this queued mutation.
     */
    suspend fun replayAttachmentDelete(id: Int, attachmentId: Int) {
        try {
            if (capabilities.useV2()) projectsV2Api.deleteAttachment(id, attachmentId)
            else projectsApi.deleteAttachment(projectId = id, attachmentId = attachmentId)
        } catch (e: HttpException) {
            if (e.code() != 404) throw e
        }
        runCatching { refreshOne(id) }
    }

    /** Image deletion is idempotent: a 404 means it is already gone. */
    suspend fun replayImageDelete(id: Int, imageId: Int) {
        removeImageFromLocalDetail(id, imageId)
        try {
            if (capabilities.useV2()) projectsV2Api.deleteImage(id, imageId)
            else projectsApi.deleteImage(projectId = id, imageId = imageId)
        } catch (e: HttpException) {
            if (e.code() != 404) throw e
        }
        runCatching { refreshOne(id) }
    }

    private suspend fun removeImageFromLocalDetail(projectId: Int, imageId: Int) {
        database.withTransaction {
            projectDao.getById(projectId)?.let { existing ->
                val current = json.decodeFromString<ProjectDto>(existing.detailJson)
                val updated = removeProjectImage(current, imageId)
                if (updated != current) projectDao.upsertAll(listOf(updated.toEntity(json)))
            }
        }
    }
}

/**
 * `ProjectResponse` (unlike `ProjectListItemResponse`) has no server-computed `preview_url` - the
 * title image's thumbnail is the client-side equivalent, falling back to the first image.
 */
internal fun ProjectDto.toEntity(json: Json): ProjectEntity =
    ProjectEntity(
        id = id,
        name = name,
        category = category,
        tagsJson = json.encodeToString(tags),
        isFavorite = isFavorite,
        status = ProjectStatus.normalize(status),
        updatedAt = DateTimeUtils.parseToEpochMillis(updatedAt),
        previewUrl = (images.firstOrNull { it.isCover() } ?: images.firstOrNull())?.thumbnailUrl,
        detailJson = json.encodeToString(this),
        ownerId = owner?.id,
        ownerName = owner?.name?.ifBlank { null } ?: owner?.email?.ifBlank { null },
        ownerAvatarUrl = owner?.avatarUrl,
        accessRole = access?.role,
        accessDerived = access?.derived ?: false,
        canEdit = access?.canEdit,
        canManage = access?.canManage,
    )

internal fun removeProjectAttachment(project: ProjectDto, attachmentId: Int): ProjectDto =
    project.copy(attachments = project.attachments.filterNot { it.id == attachmentId })

internal fun removeProjectImage(project: ProjectDto, imageId: Int): ProjectDto =
    project.copy(images = project.images.filterNot { it.id == imageId })

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
            status = ProjectStatus.normalize(status),
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
            status = status?.let(ProjectStatus::normalize) ?: current.status,
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
            previousPayloadJson?.let { payload ->
                runCatching {
                        json.decodeFromString<ProjectWriteRequest>(payload).expectedUpdatedAt
                    }
                    .getOrNull()
            } ?: expectedUpdatedAt
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
