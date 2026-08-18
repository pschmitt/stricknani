package blue.anika.wolle.data.repository

import blue.anika.wolle.data.api.SyncApi
import blue.anika.wolle.data.api.YarnsApi
import blue.anika.wolle.data.api.dto.YarnDto
import blue.anika.wolle.data.db.dao.SyncStateDao
import blue.anika.wolle.data.db.dao.YarnDao
import blue.anika.wolle.data.db.entity.SyncStateEntity
import blue.anika.wolle.data.db.entity.YarnEntity
import blue.anika.wolle.data.util.DateTimeUtils
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
                if (wasFavorite) yarnsApi.unfavoriteYarn(entity.id) else yarnsApi.favoriteYarn(entity.id)
            yarnDao.upsertAll(listOf(updated.toEntity(json)))
        } catch (e: Exception) {
            yarnDao.upsertAll(listOf(entity.copy(isFavorite = wasFavorite)))
            throw e
        }
    }

    suspend fun sync() {
        val cursor = syncStateDao.getCursor(ENTITY_TYPE)
        val response = syncApi.syncYarns(since = cursor)
        if (response.updated.isNotEmpty()) {
            yarnDao.upsertAll(response.updated.map { it.toEntity(json) })
        }
        if (response.deletedIds.isNotEmpty()) {
            yarnDao.deleteByIds(response.deletedIds)
        }
        syncStateDao.setCursor(SyncStateEntity(ENTITY_TYPE, response.serverTime))
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
