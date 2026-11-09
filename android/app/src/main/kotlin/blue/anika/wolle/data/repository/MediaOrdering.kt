package blue.anika.wolle.data.repository

import blue.anika.wolle.data.api.dto.ImageDto
import blue.anika.wolle.data.api.dto.ProjectDto
import blue.anika.wolle.data.api.dto.YarnDto
import blue.anika.wolle.data.api.dto.YarnPhotoDto
import blue.anika.wolle.data.db.entity.PendingMutationEntity
import blue.anika.wolle.data.uploads.PendingMediaOrder
import kotlinx.serialization.json.Json
import retrofit2.HttpException

/*
 * Pure helpers for ordering project images and yarn photos. The first item of a group is its
 * cover: the project's title image or the yarn's main photo. A step's images have no cover of
 * their own, so reordering them never touches the project cover.
 */

/** Moves the item at [from] to [to]; returns the list unchanged when either index is invalid. */
fun <T> List<T>.movedItem(from: Int, to: Int): List<T> {
    if (from == to || from !in indices || to !in indices) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}

/** A photo can become the cover (position 0) when it is a server photo that is not first yet. */
fun canMakeCover(index: Int, reorderableCount: Int): Boolean = index in 1 until reorderableCount

/** The server images shown in the editor's title strip (not a step's, not the stitch sample). */
fun ProjectDto.titleImages(): List<ImageDto> =
    images
        .filter { it.stepId == null && !it.isStitchSample }
        .sortedByPositionOrKeep()
        // The cover always comes first, whatever its stored position.
        .sortedByDescending { it.isCover() }

/** A project's images as the detail screen shows them: the cover first, then the server order. */
fun ProjectDto.galleryImages(): List<ImageDto> = images.sortedByDescending { it.isCover() }

/** The server's canonical cover (`is_primary`); the legacy title flag only for older payloads. */
fun ImageDto.isCover(): Boolean = isPrimary ?: isTitleImage

fun ProjectDto.stepImages(stepId: Int): List<ImageDto> =
    images.filter { it.stepId == stepId }.sortedByPositionOrKeep()

/** Stable: images without a position keep the order the server sent them in. */
fun List<ImageDto>.sortedByPositionOrKeep(): List<ImageDto> = sortedBy {
    it.position ?: Int.MAX_VALUE
}

fun List<YarnPhotoDto>.photosSortedByPosition(): List<YarnPhotoDto> = sortedBy {
    it.position ?: Int.MAX_VALUE
}

/**
 * The order to send for a group, or null when the user did not change it. [initial] is the group as
 * loaded, [current] as edited and [removed] the ids deleted in the same edit (the server drops
 * those first, so they must not be in the request).
 */
fun reorderedIdsOrNull(initial: List<Int>, current: List<Int>, removed: Set<Int>): List<Int>? {
    val expected = initial.filterNot { it in removed }
    return current.takeIf { it != expected && it.toSet() == expected.toSet() }
}

/** Applies a title-image order locally: cover flag and positions follow the new order. */
internal fun ProjectDto.withTitleImageOrder(ids: List<Int>): ProjectDto =
    withGroupOrder(ids, isCoverGroup = true) { it.stepId == null && !it.isStitchSample }

/** Applies one step's image order locally; the project cover stays as it was. */
internal fun ProjectDto.withStepImageOrder(stepId: Int, ids: List<Int>): ProjectDto =
    withGroupOrder(ids, isCoverGroup = false) { it.stepId == stepId }

private fun ProjectDto.withGroupOrder(
    ids: List<Int>,
    isCoverGroup: Boolean,
    inGroup: (ImageDto) -> Boolean,
): ProjectDto {
    val byId = images.filter(inGroup).associateBy { it.id }
    if (ids.toSet() != byId.keys || ids.size != byId.size) return this
    val ordered = ids.mapIndexed { index, id ->
        val image = byId.getValue(id)
        if (isCoverGroup)
            image.copy(position = index, isTitleImage = index == 0, isPrimary = index == 0)
        else image.copy(position = index)
    }
    // Fill the group's slots in the flat list in the new order, leaving other groups where they
    // are.
    val iterator = ordered.iterator()
    return copy(images = images.map { if (inGroup(it)) iterator.next() else it })
}

/** Applies a photo order locally: the first photo becomes the main one. */
internal fun YarnDto.withPhotoOrder(ids: List<Int>): YarnDto {
    val byId = photos.associateBy { it.id }
    if (ids.toSet() != byId.keys || ids.size != byId.size) return this
    return copy(
        photos =
            ids.mapIndexed { index, id ->
                byId.getValue(id).copy(position = index, isPrimary = index == 0)
            }
    )
}

/** The queued order of the same group (same [stepId]) that a newer reorder should replace. */
internal fun findQueuedOrder(
    queued: List<PendingMutationEntity>,
    stepId: Int?,
    json: Json,
): PendingMutationEntity? = queued.firstOrNull {
    runCatching { json.decodeFromString<PendingMediaOrder>(it.payloadJson.orEmpty()).stepId }
        .getOrNull() == stepId
}

/** Replay answers that mean a queued order can never apply: gone, no such route, or stale. */
private val DROPPED_ORDER_STATUSES = setOf(404, 405, 422)

/**
 * Sends a queued order. Returns false when the server rejected it for good (404 wrong owner or
 * step, 405 no such route, 422 the group no longer matches the order): the caller drops the action
 * and resyncs the entity. Any other failure is thrown so the action stays queued.
 */
internal suspend fun sendMediaOrder(send: suspend () -> Unit): Boolean =
    try {
        send()
        true
    } catch (e: HttpException) {
        if (e.code() !in DROPPED_ORDER_STATUSES) throw e
        false
    }
