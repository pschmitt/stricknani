package blue.anika.wolle.data.repository

import blue.anika.wolle.data.api.SyncApi
import blue.anika.wolle.data.api.YarnsApi
import blue.anika.wolle.data.api.dto.YarnDto
import blue.anika.wolle.data.api.dto.YarnWriteRequest
import blue.anika.wolle.data.db.dao.PendingMutationDao
import blue.anika.wolle.data.db.dao.SyncStateDao
import blue.anika.wolle.data.db.dao.YarnDao
import blue.anika.wolle.data.db.entity.MutationEntityType
import blue.anika.wolle.data.db.entity.MutationOperation
import blue.anika.wolle.data.db.entity.PendingMutationEntity
import blue.anika.wolle.data.db.entity.SyncStateEntity
import blue.anika.wolle.data.db.entity.YarnEntity
import blue.anika.wolle.data.util.DateTimeUtils
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

private const val ENTITY_TYPE = "yarn"

/** Cache-first access to yarns, delta-synced against `/api/v1/sync/yarns`. */
@Singleton
class YarnRepository
@Inject
constructor(
    private val yarnDao: YarnDao,
    private val syncStateDao: SyncStateDao,
    private val syncApi: SyncApi,
    private val yarnsApi: YarnsApi,
    private val pendingMutationDao: PendingMutationDao,
    private val json: Json,
) {
    fun observeAll(): Flow<List<YarnEntity>> = yarnDao.observeAll()

    fun observeById(id: Int): Flow<YarnEntity?> = yarnDao.observeById(id)

    fun decodeDetail(entity: YarnEntity): YarnDto = json.decodeFromString(entity.detailJson)

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
        if (response.updated.isNotEmpty()) {
            yarnDao.upsertAll(response.updated.map { it.toEntity(json) })
        }
        if (response.deletedIds.isNotEmpty()) {
            yarnDao.deleteByIds(response.deletedIds)
        }
        syncStateDao.setCursor(SyncStateEntity(ENTITY_TYPE, response.serverTime))
        return response.updated.isNotEmpty() || response.deletedIds.isNotEmpty()
    }

    /** See `ProjectRepository.createProject`'s kdoc - same placeholder-row-plus-queue pattern. */
    suspend fun createYarn(request: YarnWriteRequest): Int {
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
        return tempId
    }

    suspend fun updateYarn(id: Int, request: YarnWriteRequest) {
        val existing = yarnDao.getById(id) ?: return
        yarnDao.upsertAll(listOf(request.applyTo(existing, json)))
        pendingMutationDao.insert(
            PendingMutationEntity(
                entityType = MutationEntityType.YARN,
                operation = MutationOperation.UPDATE,
                localId = id,
                payloadJson = json.encodeToString(request),
                createdAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun deleteYarn(id: Int) {
        yarnDao.deleteByIds(listOf(id))
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

    /** Called only by `WriteReplayWorker` once a queued create actually reaches the server. */
    suspend fun replayCreate(tempId: Int, request: YarnWriteRequest): Int {
        val created = yarnsApi.createYarn(request)
        yarnDao.deleteByIds(listOf(tempId))
        yarnDao.upsertAll(listOf(created.toEntity(json)))
        return created.id
    }

    /** Called only by `WriteReplayWorker`. */
    suspend fun replayUpdate(id: Int, request: YarnWriteRequest) {
        val updated = yarnsApi.updateYarn(id, request)
        yarnDao.upsertAll(listOf(updated.toEntity(json)))
    }

    /** Called only by `WriteReplayWorker`. */
    suspend fun replayDelete(id: Int) {
        yarnsApi.deleteYarn(id)
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
