package blue.anika.wolle.data.repository

import androidx.room.withTransaction
import blue.anika.wolle.data.api.SyncApi
import blue.anika.wolle.data.api.YarnsApi
import blue.anika.wolle.data.api.dto.YarnDto
import blue.anika.wolle.data.api.dto.YarnWriteRequest
import blue.anika.wolle.data.db.AppDatabase
import blue.anika.wolle.data.db.dao.PendingMutationDao
import blue.anika.wolle.data.db.dao.SyncStateDao
import blue.anika.wolle.data.db.dao.YarnDao
import blue.anika.wolle.data.db.entity.MutationEntityType
import blue.anika.wolle.data.db.entity.MutationOperation
import blue.anika.wolle.data.db.entity.PendingMutationEntity
import blue.anika.wolle.data.db.entity.SyncStateEntity
import blue.anika.wolle.data.db.entity.YarnEntity
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

private const val ENTITY_TYPE = "yarn"

data class YarnRefreshResult(val detail: YarnDto, val changed: Boolean)

/** Cache-first access to yarns, delta-synced against `/api/v1/sync/yarns`. */
@Singleton
class YarnRepository
@Inject
constructor(
    private val database: AppDatabase,
    private val yarnDao: YarnDao,
    private val syncStateDao: SyncStateDao,
    private val syncApi: SyncApi,
    private val yarnsApi: YarnsApi,
    private val pendingMutationDao: PendingMutationDao,
    private val pendingUploadStore: PendingUploadStore,
    private val json: Json,
) {
    fun observeAll(): Flow<List<YarnEntity>> = yarnDao.observeAll()

    fun observeById(id: Int): Flow<YarnEntity?> = yarnDao.observeById(id)

    fun decodeDetail(entity: YarnEntity): YarnDto = json.decodeFromString(entity.detailJson)

    /** Refreshes one yarn and returns its detail so a detail screen can refresh linked projects. */
    suspend fun refreshOne(id: Int): YarnRefreshResult {
        val detail = yarnsApi.getYarn(id)
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
                if (wasFavorite) yarnsApi.unfavoriteYarn(entity.id)
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
    suspend fun sync(): Boolean {
        val cursor = syncStateDao.getCursor(ENTITY_TYPE)
        val response = syncApi.syncYarns(since = cursor)
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
            ?.let { yarnDao.upsertAll(it.map { yarn -> yarn.toEntity(json) }) }
        response.deletedIds
            .filterNot { it in pendingDeletedIds }
            .takeIf { it.isNotEmpty() }
            ?.let { yarnDao.deleteByIds(it) }
        syncStateDao.setCursor(SyncStateEntity(ENTITY_TYPE, response.serverTime))
        return response.updated.isNotEmpty() || response.deletedIds.isNotEmpty()
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

    /** Called only by `WriteReplayWorker` once a queued create actually reaches the server. */
    suspend fun replayCreate(tempId: Int, request: YarnWriteRequest): Int {
        val created = yarnsApi.createYarn(request)
        database.withTransaction {
            yarnDao.deleteByIds(listOf(tempId))
            yarnDao.upsertAll(listOf(created.toEntity(json)))
            pendingMutationDao.reassignLocalId(MutationEntityType.YARN, tempId, created.id)
        }
        return created.id
    }

    /** Called only by `WriteReplayWorker` - a 409 (SNA-33 conflict) propagates to its caller. */
    suspend fun replayUpdate(id: Int, request: YarnWriteRequest) {
        val updated = yarnsApi.updateYarn(id, request)
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
            yarnsApi.deleteYarn(id)
        } catch (e: HttpException) {
            if (e.code() != 404) throw e
        }
    }

    /** Called only by [blue.anika.wolle.sync.WriteReplayWorker]. */
    suspend fun replayPhotoUpload(id: Int, upload: PendingUpload) {
        yarnsApi.uploadPhoto(id, pendingUploadStore.multipart(upload))
        pendingUploadStore.delete(upload)
        runCatching { refreshOne(id) }
    }
}

/**
 * `YarnResponse` (unlike `YarnListItemResponse`) has no server-computed `preview_url` - the primary
 * photo's thumbnail is the client-side equivalent, falling back to the first photo.
 */
private fun YarnDto.toEntity(json: Json): YarnEntity =
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
