package blue.anika.wolle.data.repository

import blue.anika.wolle.data.api.ProjectsApi
import blue.anika.wolle.data.api.SyncApi
import blue.anika.wolle.data.api.dto.ProjectDto
import blue.anika.wolle.data.api.dto.ProjectWriteRequest
import blue.anika.wolle.data.db.dao.PendingMutationDao
import blue.anika.wolle.data.db.dao.ProjectDao
import blue.anika.wolle.data.db.dao.SyncStateDao
import blue.anika.wolle.data.db.entity.MutationEntityType
import blue.anika.wolle.data.db.entity.MutationOperation
import blue.anika.wolle.data.db.entity.PendingMutationEntity
import blue.anika.wolle.data.db.entity.ProjectEntity
import blue.anika.wolle.data.db.entity.SyncStateEntity
import blue.anika.wolle.data.util.DateTimeUtils
import java.time.OffsetDateTime
import java.time.ZoneOffset
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
    private val projectsApi: ProjectsApi,
    private val pendingMutationDao: PendingMutationDao,
    private val json: Json,
) {
    fun observeAll(): Flow<List<ProjectEntity>> = projectDao.observeAll()

    fun observeById(id: Int): Flow<ProjectEntity?> = projectDao.observeById(id)

    fun decodeDetail(entity: ProjectEntity): ProjectDto = json.decodeFromString(entity.detailJson)

    fun decodeTags(entity: ProjectEntity): List<String> = json.decodeFromString(entity.tagsJson)

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
        if (response.updated.isNotEmpty()) {
            projectDao.upsertAll(response.updated.map { it.toEntity(json) })
        }
        if (response.deletedIds.isNotEmpty()) {
            projectDao.deleteByIds(response.deletedIds)
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
        return tempId
    }

    /** Applies the edit to the local row immediately and queues an [MutationOperation.UPDATE]. */
    suspend fun updateProject(id: Int, request: ProjectWriteRequest) {
        val existing = projectDao.getById(id) ?: return
        projectDao.upsertAll(listOf(request.applyTo(existing, json)))
        pendingMutationDao.insert(
            PendingMutationEntity(
                entityType = MutationEntityType.PROJECT,
                operation = MutationOperation.UPDATE,
                localId = id,
                payloadJson = json.encodeToString(request),
                createdAt = System.currentTimeMillis(),
            )
        )
    }

    /** Removes the local row immediately and queues a [MutationOperation.DELETE]. */
    suspend fun deleteProject(id: Int) {
        projectDao.deleteByIds(listOf(id))
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

    /** Called only by `WriteReplayWorker` once a queued create actually reaches the server. */
    suspend fun replayCreate(tempId: Int, request: ProjectWriteRequest): Int {
        val created = projectsApi.createProject(request)
        projectDao.deleteByIds(listOf(tempId))
        projectDao.upsertAll(listOf(created.toEntity(json)))
        return created.id
    }

    /** Called only by `WriteReplayWorker`. */
    suspend fun replayUpdate(id: Int, request: ProjectWriteRequest) {
        val updated = projectsApi.updateProject(id, request)
        projectDao.upsertAll(listOf(updated.toEntity(json)))
    }

    /** Called only by `WriteReplayWorker`. */
    suspend fun replayDelete(id: Int) {
        projectsApi.deleteProject(id)
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
            steps = emptyList(),
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
            updatedAt = nowIso(),
        )
    return updated.toEntity(json)
}
