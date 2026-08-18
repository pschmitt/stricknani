package blue.anika.wolle.data.repository

import blue.anika.wolle.data.api.SyncApi
import blue.anika.wolle.data.api.dto.ProjectDto
import blue.anika.wolle.data.db.dao.ProjectDao
import blue.anika.wolle.data.db.dao.SyncStateDao
import blue.anika.wolle.data.db.entity.ProjectEntity
import blue.anika.wolle.data.db.entity.SyncStateEntity
import blue.anika.wolle.data.util.DateTimeUtils
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

private const val ENTITY_TYPE = "project"

/** Cache-first access to projects, delta-synced against `/api/v1/sync/projects`. */
@Singleton
class ProjectRepository
@Inject
constructor(
    private val projectDao: ProjectDao,
    private val syncStateDao: SyncStateDao,
    private val syncApi: SyncApi,
    private val json: Json,
) {
    fun observeAll(): Flow<List<ProjectEntity>> = projectDao.observeAll()

    fun observeById(id: Int): Flow<ProjectEntity?> = projectDao.observeById(id)

    fun decodeDetail(entity: ProjectEntity): ProjectDto = json.decodeFromString(entity.detailJson)

    fun decodeTags(entity: ProjectEntity): List<String> = json.decodeFromString(entity.tagsJson)

    suspend fun sync() {
        val cursor = syncStateDao.getCursor(ENTITY_TYPE)
        val response = syncApi.syncProjects(since = cursor)
        if (response.updated.isNotEmpty()) {
            projectDao.upsertAll(response.updated.map { it.toEntity(json) })
        }
        if (response.deletedIds.isNotEmpty()) {
            projectDao.deleteByIds(response.deletedIds)
        }
        syncStateDao.setCursor(SyncStateEntity(ENTITY_TYPE, response.serverTime))
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
