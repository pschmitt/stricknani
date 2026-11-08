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
 * - Returns whether the batch changed the cache: a row that arrives identical to the cached one
 *   (typically this device's own write coming back) or a tombstone for a row that is not cached
 *   does not count, so a background sync does not announce news that is not new.
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
): Boolean =
    applyItemDeltaDetailed(
            updated,
            deletedIds,
            idOf,
            toEntity,
            pendingIds,
            upsert,
            load,
            delete,
            evictMedia,
        )
        .changed

/** Same as [applyItemDelta], but says which rows changed (for the sync notification). */
internal suspend fun <D, E> applyItemDeltaDetailed(
    updated: List<D>,
    deletedIds: List<Int>,
    idOf: (D) -> Int,
    toEntity: (D) -> E,
    pendingIds: suspend (List<Int>) -> Set<Int>,
    upsert: suspend (List<E>) -> Unit,
    load: suspend (Int) -> E?,
    delete: suspend (List<Int>) -> Unit,
    evictMedia: suspend (E) -> Unit,
): ItemSyncResult {
    val updatedIds = updated.map(idOf)
    val pendingUpdated = if (updatedIds.isEmpty()) emptySet() else pendingIds(updatedIds)
    val pendingDeleted = if (deletedIds.isEmpty()) emptySet() else pendingIds(deletedIds)
    val removable = deletedIds.filterNot { it in pendingDeleted }
    val returning = updatedIds.toSet()
    // Rows as cached before this batch, for the "did anything really change" answer.
    val before = mutableMapOf<Int, E?>()
    for (id in (removable + updatedIds.filterNot { it in pendingUpdated }).distinct()) {
        before[id] = load(id)
    }
    val changedIds = linkedSetOf<Int>()
    var deleted = 0
    if (removable.isNotEmpty()) {
        removable
            .filterNot { it in returning }
            .forEach { id ->
                before[id]?.let {
                    deleted++
                    runCatching { evictMedia(it) }
                }
            }
        delete(removable)
    }
    val incoming = updated.filterNot { idOf(it) in pendingUpdated }.map { idOf(it) to toEntity(it) }
    if (incoming.isNotEmpty()) {
        incoming.forEach { (id, entity) -> if (before[id] != entity) changedIds += id }
        upsert(incoming.map { it.second })
    }
    return ItemSyncResult(upsertedIds = changedIds, deletedCount = deleted)
}

/**
 * What a project or yarn sync really changed: the server ids of rows that were added or modified
 * (this device's own writes coming back are not in it) and how many cached rows were removed.
 */
data class ItemSyncResult(val upsertedIds: Set<Int> = emptySet(), val deletedCount: Int = 0) {
    val changed: Boolean
        get() = upsertedIds.isNotEmpty() || deletedCount > 0

    operator fun plus(other: ItemSyncResult): ItemSyncResult =
        ItemSyncResult(upsertedIds + other.upsertedIds, deletedCount + other.deletedCount)

    companion object {
        val None = ItemSyncResult()
    }
}

/** Server media paths of a project: images, thumbnails and attachment files. */
internal fun ProjectDto.mediaPaths(): List<String> =
    images.flatMap { listOf(it.url, it.thumbnailUrl) } + attachments.map { it.url }

/** Server media paths of a yarn: photos and their thumbnails. */
internal fun YarnDto.mediaPaths(): List<String> = photos.flatMap { listOf(it.url, it.thumbnailUrl) }
