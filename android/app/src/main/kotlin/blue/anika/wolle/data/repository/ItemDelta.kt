package blue.anika.wolle.data.repository

import blue.anika.wolle.data.api.dto.ProjectDto
import blue.anika.wolle.data.api.dto.YarnDto

/**
 * Applies one batch of a sync response (`docs/sharing-api.md`, "Sync") to the cache.
 *
 * - `deleted_ids` first, `updated` second: an item that left and came back is in both and must end
 *   up present.
 * - `updated` entries are applied by id, always (a newly shared or derived item is older than the
 *   cursor), except ids with a queued local write, which win until replayed.
 * - A tombstone means "gone or no longer visible": the row goes, and so does its cached media, so
 *   nothing of somebody else's item lingers on the device. Tombstoned ids with a queued local write
 *   stay until the replay learns the same (404). Owned data is only ever removed by its own
 *   tombstone (id match), never by a scope or owner heuristic.
 * - Returns whether the batch carried any change.
 */
internal suspend fun <D, E> applyItemDelta(
    updated: List<D>,
    deletedIds: List<Int>,
    idOf: (D) -> Int,
    toEntity: (D) -> E,
    pendingIds: suspend (List<Int>) -> Set<Int>,
    upsert: suspend (List<E>) -> Unit,
    load: suspend (Int) -> E?,
    delete: suspend (List<Int>) -> Unit,
    evictMedia: suspend (E) -> Unit,
): Boolean {
    val updatedIds = updated.map(idOf)
    val pendingUpdated = if (updatedIds.isEmpty()) emptySet() else pendingIds(updatedIds)
    val pendingDeleted = if (deletedIds.isEmpty()) emptySet() else pendingIds(deletedIds)
    val removable = deletedIds.filterNot { it in pendingDeleted }
    if (removable.isNotEmpty()) {
        val returning = updatedIds.toSet()
        removable
            .filterNot { it in returning }
            .forEach { id ->
                load(id)?.let { runCatching { evictMedia(it) } }
            }
        delete(removable)
    }
    updated
        .filterNot { idOf(it) in pendingUpdated }
        .takeIf { it.isNotEmpty() }
        ?.let { upsert(it.map(toEntity)) }
    return updated.isNotEmpty() || deletedIds.isNotEmpty()
}

/** Server media paths of a project: images, thumbnails and attachment files. */
internal fun ProjectDto.mediaPaths(): List<String> =
    images.flatMap { listOf(it.url, it.thumbnailUrl) } + attachments.map { it.url }

/** Server media paths of a yarn: photos and their thumbnails. */
internal fun YarnDto.mediaPaths(): List<String> = photos.flatMap { listOf(it.url, it.thumbnailUrl) }
