package blue.anika.wolle.data.repository

import androidx.room.withTransaction
import blue.anika.wolle.data.api.ApiCapabilities
import blue.anika.wolle.data.api.SyncApi
import blue.anika.wolle.data.api.YarnsApi
import blue.anika.wolle.data.api.dto.YarnDto
import blue.anika.wolle.data.api.dto.YarnWriteRequest
import blue.anika.wolle.data.api.v2.MediaOrderRequest
import blue.anika.wolle.data.api.v2.SyncV2Api
import blue.anika.wolle.data.api.v2.YarnsV2Api
import blue.anika.wolle.data.api.v2.runV2Sync
import blue.anika.wolle.data.api.v2.staleAfterFullResync
import blue.anika.wolle.data.api.v2.toV1
import blue.anika.wolle.data.db.AppDatabase
import blue.anika.wolle.data.db.dao.PendingMutationDao
import blue.anika.wolle.data.db.dao.SyncStateDao
import blue.anika.wolle.data.db.dao.YarnDao
import blue.anika.wolle.data.db.entity.MutationEntityType
import blue.anika.wolle.data.db.entity.MutationOperation
import blue.anika.wolle.data.db.entity.PendingMutationEntity
import blue.anika.wolle.data.db.entity.SyncStateEntity
import blue.anika.wolle.data.db.entity.YarnEntity
import blue.anika.wolle.data.uploads.PendingGalleryImageDelete
import blue.anika.wolle.data.uploads.PendingMediaOrder
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
import retrofit2.HttpException
import timber.log.Timber

private const val ENTITY_TYPE = "yarn"

data class YarnRefreshResult(val detail: YarnDto, val changed: Boolean)

/**
 * Cache-first access to yarns, delta-synced against `/api/v2/sync/yarns` (v1 on servers without
 * v2).
 */
@Singleton
class YarnRepository
@Inject
constructor(
    private val database: AppDatabase,
    private val yarnDao: YarnDao,
    private val syncStateDao: SyncStateDao,
    private val syncApi: SyncApi,
    private val syncV2Api: SyncV2Api,
    private val yarnsApi: YarnsApi,
    private val yarnsV2Api: YarnsV2Api,
    private val capabilities: ApiCapabilities,
    private val pendingMutationDao: PendingMutationDao,
    private val pendingUploadStore: PendingUploadStore,
    private val json: Json,
) {
    fun observeAll(): Flow<List<YarnEntity>> = yarnDao.observeAll()

    fun observeById(id: Int): Flow<YarnEntity?> = yarnDao.observeById(id)

    fun decodeDetail(entity: YarnEntity): YarnDto = json.decodeFromString(entity.detailJson)

    /** Refreshes one yarn and returns its detail so a detail screen can refresh linked projects. */
    suspend fun refreshOne(id: Int): YarnRefreshResult {
        val detail =
            if (capabilities.useV2()) yarnsV2Api.getYarn(id).data.toV1() else yarnsApi.getYarn(id)
        val refreshed = detail.toEntity(json)
        val changed = yarnDao.getById(id)?.let { it != refreshed } ?: true
        yarnDao.upsertAll(listOf(refreshed))
        return YarnRefreshResult(detail = detail, changed = changed)
    }

    /** See `ProjectRepository.toggleFavorite`'s kdoc - same optimistic-with-rollback pattern. */
    suspend fun toggleFavorite(entity: YarnEntity, wasFavorite: Boolean) {
        yarnDao.upsertAll(listOf(entity.copy(isFavorite = !wasFavorite)))
        try {
            val updated =
                if (capabilities.useV2()) {
                    val response =
                        if (wasFavorite) yarnsV2Api.unfavoriteYarn(entity.id)
                        else yarnsV2Api.favoriteYarn(entity.id)
                    response.data.toV1()
                } else if (wasFavorite) yarnsApi.unfavoriteYarn(entity.id)
                else yarnsApi.favoriteYarn(entity.id)
            yarnDao.upsertAll(listOf(updated.toEntity(json)))
        } catch (e: Exception) {
            yarnDao.upsertAll(listOf(entity.copy(isFavorite = wasFavorite)))
            throw e
        }
    }

    /**
     * Returns whether this sync actually pulled any change - see SyncWorker/SyncNotifier (SNA-14).
     */
    suspend fun sync(): Boolean = if (capabilities.useV2()) syncV2() else syncV1()

    private suspend fun syncV1(): Boolean {
        val cursor = syncStateDao.getCursor(ENTITY_TYPE)
        val response = syncApi.syncYarns(since = cursor)
        val changed = applyDelta(response.updated, response.deletedIds)
        syncStateDao.setCursor(SyncStateEntity(ENTITY_TYPE, response.serverTime))
        return changed
    }

    /**
     * `GET /api/v2/sync/yarns` with cursor paging and `full_resync_required` handling (see
     * [runV2Sync]). The delta semantics are those of [syncV1]: pending local writes win, tombstones
     * delete, and the new cursor is stored only after every page was applied.
     */
    private suspend fun syncV2(): Boolean {
        var changed = false
        val outcome =
            runV2Sync(
                since = syncStateDao.getCursor(ENTITY_TYPE),
                idOf = { it.id },
                fetch = { since, cursor ->
                    syncV2Api.syncYarns(since = since, cursor = cursor).data
                },
                onPage = { updated, deletedIds ->
                    if (applyDelta(updated.map { it.toV1() }, deletedIds)) changed = true
                },
            )
        if (outcome.fullResync) {
            val localIds = yarnDao.getServerIds()
            val pending =
                localIds
                    .takeIf { it.isNotEmpty() }
                    ?.let { pendingMutationDao.getReplayableLocalIds(ENTITY_TYPE, it).toSet() }
                    ?: emptySet()
            val stale = staleAfterFullResync(localIds, outcome.seenIds, pending)
            if (stale.isNotEmpty()) {
                yarnDao.deleteByIds(stale)
                changed = true
            }
        }
        syncStateDao.setCursor(SyncStateEntity(ENTITY_TYPE, outcome.serverTime))
        return changed
    }

    /** Applies one batch of server changes to Room; returns whether it carried any change. */
    private suspend fun applyDelta(updated: List<YarnDto>, deletedIds: List<Int>): Boolean {
        val pendingUpdatedIds =
            updated
                .map { it.id }
                .takeIf { it.isNotEmpty() }
                ?.let { pendingMutationDao.getReplayableLocalIds(ENTITY_TYPE, it).toSet() }
                ?: emptySet()
        val pendingDeletedIds =
            deletedIds
                .takeIf { it.isNotEmpty() }
                ?.let { pendingMutationDao.getReplayableLocalIds(ENTITY_TYPE, it).toSet() }
                ?: emptySet()
        updated
            .filterNot { it.id in pendingUpdatedIds }
            .takeIf { it.isNotEmpty() }
            ?.let { yarnDao.upsertAll(it.map { item -> item.toEntity(json) }) }
        deletedIds
            .filterNot { it in pendingDeletedIds }
            .takeIf { it.isNotEmpty() }
            ?.let { yarnDao.deleteByIds(it) }
        return updated.isNotEmpty() || deletedIds.isNotEmpty()
    }

    /** See `ProjectRepository.createProject`'s kdoc - same placeholder-row-plus-queue pattern. */
    suspend fun createYarn(request: YarnWriteRequest): Int {
        return database.withTransaction {
            val tempId = minOf(yarnDao.minId() ?: 0, 0) - 1
            yarnDao.upsertAll(listOf(request.toPlaceholderEntity(tempId, json)))
            pendingMutationDao.insert(
                PendingMutationEntity(
                    entityType = MutationEntityType.YARN,
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
     * See `ProjectRepository.updateProject`'s kdoc - same `expectedUpdatedAt` stamping (SNA-33).
     */
    suspend fun updateYarn(id: Int, request: YarnWriteRequest) {
        database.withTransaction {
            val existing = yarnDao.getById(id) ?: return@withTransaction
            yarnDao.upsertAll(listOf(request.applyTo(existing, json)))
            pendingMutationDao.deleteResolvedConflicts(MutationEntityType.YARN, id)
            val queued =
                pendingMutationDao.getReplayable(
                    MutationEntityType.YARN,
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
                        entityType = MutationEntityType.YARN,
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

    suspend fun deleteYarn(id: Int) {
        database.withTransaction {
            yarnDao.deleteByIds(listOf(id))
            pendingMutationDao.deleteResolvedConflicts(MutationEntityType.YARN, id)
            pendingMutationDao.insert(
                PendingMutationEntity(
                    entityType = MutationEntityType.YARN,
                    operation = MutationOperation.DELETE,
                    localId = id,
                    payloadJson = null,
                    createdAt = System.currentTimeMillis(),
                )
            )
        }
    }

    suspend fun queuePhotoUpload(yarnId: Int, upload: PendingUpload) {
        database.withTransaction {
            pendingMutationDao.insert(
                PendingMutationEntity(
                    entityType = MutationEntityType.YARN,
                    operation = MutationOperation.YARN_PHOTO_UPLOAD,
                    localId = yarnId,
                    payloadJson = json.encodeToString(upload),
                    createdAt = System.currentTimeMillis(),
                )
            )
        }
    }

    /** Removes a photo from the local detail immediately and queues its server deletion. */
    suspend fun queuePhotoDelete(yarnId: Int, photoId: Int) {
        database.withTransaction {
            yarnDao.getById(yarnId)?.let { existing ->
                val current = json.decodeFromString<YarnDto>(existing.detailJson)
                val updated = removeYarnPhoto(current, photoId)
                if (updated != current) yarnDao.upsertAll(listOf(updated.toEntity(json)))
            }
            pendingMutationDao.deleteResolvedConflicts(MutationEntityType.YARN, yarnId)
            pendingMutationDao.insert(
                PendingMutationEntity(
                    entityType = MutationEntityType.YARN,
                    operation = MutationOperation.YARN_PHOTO_DELETE,
                    localId = yarnId,
                    payloadJson = json.encodeToString(PendingGalleryImageDelete(photoId)),
                    createdAt = System.currentTimeMillis(),
                )
            )
        }
    }

    /**
     * Applies a new photo order to the cached detail at once and queues it for replay. A second
     * reorder before the first replayed replaces it, so only the latest order is sent.
     */
    suspend fun queuePhotoOrder(yarnId: Int, ids: List<Int>) {
        database.withTransaction {
            yarnDao.getById(yarnId)?.let { existing ->
                val current = json.decodeFromString<YarnDto>(existing.detailJson)
                val updated = current.withPhotoOrder(ids)
                if (updated != current) yarnDao.upsertAll(listOf(updated.toEntity(json)))
            }
            pendingMutationDao.deleteResolvedConflicts(MutationEntityType.YARN, yarnId)
            val queued =
                pendingMutationDao.getReplayable(
                    MutationEntityType.YARN,
                    MutationOperation.YARN_PHOTO_ORDER,
                    yarnId,
                )
            val payload = json.encodeToString(PendingMediaOrder(ids))
            if (queued == null) {
                pendingMutationDao.insert(
                    PendingMutationEntity(
                        entityType = MutationEntityType.YARN,
                        operation = MutationOperation.YARN_PHOTO_ORDER,
                        localId = yarnId,
                        payloadJson = payload,
                        createdAt = System.currentTimeMillis(),
                    )
                )
            } else {
                pendingMutationDao.replacePayload(queued.id, payload)
            }
        }
    }

    /**
     * Called only by [blue.anika.wolle.sync.WriteReplayWorker]. 404, 405 and 422 mean the order can
     * never apply (the yarn or its photos changed elsewhere, or the server has no such route): the
     * action is dropped by returning normally and the yarn is re-read. Other failures stay queued.
     */
    suspend fun replayPhotoOrder(id: Int, order: PendingMediaOrder) {
        if (!sendMediaOrder { yarnsV2Api.reorderPhotos(id, MediaOrderRequest(order.ids)) }) {
            Timber.i("Dropped queued photo order for yarn $id")
        }
        runCatching { refreshOne(id) }
    }

    /** Called only by `WriteReplayWorker` once a queued create actually reaches the server. */
    suspend fun replayCreate(tempId: Int, request: YarnWriteRequest): Int {
        val created =
            if (capabilities.useV2()) yarnsV2Api.createYarn(request).data.toV1()
            else yarnsApi.createYarn(request)
        database.withTransaction {
            yarnDao.deleteByIds(listOf(tempId))
            yarnDao.upsertAll(listOf(created.toEntity(json)))
            pendingMutationDao.reassignLocalId(MutationEntityType.YARN, tempId, created.id)
        }
        return created.id
    }

    /** Called only by `WriteReplayWorker` - a 409 (SNA-33 conflict) propagates to its caller. */
    suspend fun replayUpdate(id: Int, request: YarnWriteRequest) {
        val updated =
            if (capabilities.useV2()) yarnsV2Api.updateYarn(id, request).data.toV1()
            else yarnsApi.updateYarn(id, request)
        yarnDao.upsertAll(listOf(updated.toEntity(json)))
    }

    /** See `ProjectRepository.adoptRemoteProject`'s kdoc - same SNA-33 conflict-adoption role. */
    suspend fun adoptRemoteYarn(dto: YarnDto) {
        yarnDao.upsertAll(listOf(dto.toEntity(json)))
    }

    /** See `ProjectRepository.dropDeletedProject`'s kdoc - same SNA-33 race-with-sync handling. */
    suspend fun dropDeletedYarn(id: Int) {
        yarnDao.deleteByIds(listOf(id))
    }

    /** See `ProjectRepository.replayDelete`'s kdoc - a 404 is success, not a failure (SNA-33). */
    suspend fun replayDelete(id: Int) {
        try {
            if (capabilities.useV2()) yarnsV2Api.deleteYarn(id) else yarnsApi.deleteYarn(id)
        } catch (e: HttpException) {
            if (e.code() != 404) throw e
        }
    }

    /** Called only by [blue.anika.wolle.sync.WriteReplayWorker]. */
    suspend fun replayPhotoUpload(id: Int, upload: PendingUpload) {
        val file = pendingUploadStore.multipart(upload)
        if (capabilities.useV2()) yarnsV2Api.uploadPhoto(id, file)
        else yarnsApi.uploadPhoto(id, file)
        pendingUploadStore.delete(upload)
        runCatching { refreshOne(id) }
    }

    /** Photo deletion is idempotent: a 404 means the photo is already gone. */
    suspend fun replayPhotoDelete(id: Int, photoId: Int) {
        removePhotoFromLocalDetail(id, photoId)
        try {
            if (capabilities.useV2()) yarnsV2Api.deletePhoto(id, photoId)
            else yarnsApi.deletePhoto(yarnId = id, photoId = photoId)
        } catch (e: HttpException) {
            if (e.code() != 404) throw e
        }
        runCatching { refreshOne(id) }
    }

    private suspend fun removePhotoFromLocalDetail(yarnId: Int, photoId: Int) {
        database.withTransaction {
            yarnDao.getById(yarnId)?.let { existing ->
                val current = json.decodeFromString<YarnDto>(existing.detailJson)
                val updated = removeYarnPhoto(current, photoId)
                if (updated != current) yarnDao.upsertAll(listOf(updated.toEntity(json)))
            }
        }
    }
}

/**
 * `YarnResponse` (unlike `YarnListItemResponse`) has no server-computed `preview_url` - the primary
 * photo's thumbnail is the client-side equivalent, falling back to the first photo.
 */
internal fun YarnDto.toEntity(json: Json): YarnEntity =
    YarnEntity(
        id = id,
        name = name,
        brand = brand,
        colorway = colorway,
        weightCategory = weightCategory,
        isFavorite = isFavorite,
        updatedAt = DateTimeUtils.parseToEpochMillis(updatedAt),
        previewUrl = (photos.firstOrNull { it.isPrimary } ?: photos.firstOrNull())?.thumbnailUrl,
        detailJson = json.encodeToString(this),
    )

internal fun removeYarnPhoto(yarn: YarnDto, photoId: Int): YarnDto =
    yarn.copy(photos = yarn.photos.filterNot { it.id == photoId })

private fun nowIso(): String = OffsetDateTime.now(ZoneOffset.UTC).toString()

/** A not-yet-synced local row, shown immediately while the queued create is in flight. */
private fun YarnWriteRequest.toPlaceholderEntity(tempId: Int, json: Json): YarnEntity {
    val now = nowIso()
    return YarnDto(
            id = tempId,
            name = name,
            description = description,
            brand = brand,
            colorway = colorway,
            dyeLot = dyeLot,
            fiberContent = fiberContent,
            weightCategory = weightCategory,
            recommendedNeedles = recommendedNeedles,
            weightGrams = weightGrams,
            lengthMeters = lengthMeters,
            notes = notes,
            link = link,
            linkArchive = null,
            isAiEnhanced = isAiEnhanced,
            isFavorite = false,
            createdAt = now,
            updatedAt = now,
            projectIds = emptyList(),
            photos = emptyList(),
        )
        .toEntity(json)
}

/**
 * Applies edited fields onto the cached detail, preserving what the form doesn't touch (photos
 * /favorite state/linked project ids).
 */
private fun YarnWriteRequest.applyTo(existing: YarnEntity, json: Json): YarnEntity {
    val current = json.decodeFromString<YarnDto>(existing.detailJson)
    val updated =
        current.copy(
            name = name,
            description = description,
            brand = brand,
            colorway = colorway,
            dyeLot = dyeLot,
            fiberContent = fiberContent,
            weightCategory = weightCategory,
            recommendedNeedles = recommendedNeedles,
            weightGrams = weightGrams,
            lengthMeters = lengthMeters,
            notes = notes,
            link = link,
            isAiEnhanced = isAiEnhanced,
            updatedAt = nowIso(),
        )
    return updated.toEntity(json)
}

/** See [ProjectWriteRequest.withExpectedUpdatedAt] for why temporary ids omit the precondition. */
internal fun YarnWriteRequest.withExpectedUpdatedAt(
    localId: Int,
    existingDetailJson: String,
    json: Json,
): YarnWriteRequest =
    copy(
        expectedUpdatedAt =
            localId
                .takeIf { it > 0 }
                ?.let { json.decodeFromString<YarnDto>(existingDetailJson).updatedAt }
    )

/** Keeps the original server snapshot when several local edits are coalesced before replay. */
internal fun YarnWriteRequest.coalesceExpectedUpdatedAt(
    previousPayloadJson: String?,
    json: Json,
): YarnWriteRequest =
    copy(
        expectedUpdatedAt =
            previousPayloadJson?.let { payload ->
                runCatching {
                        json.decodeFromString<YarnWriteRequest>(payload).expectedUpdatedAt
                    }
                    .getOrNull()
            } ?: expectedUpdatedAt
    )
