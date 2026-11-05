package blue.anika.wolle.data.repository

import blue.anika.wolle.data.api.dto.ProjectDto
import blue.anika.wolle.data.api.dto.ProjectWriteRequest

/**
 * Whether a successful local media reorder is the only server change since a queued project edit.
 *
 * Reordering changes the project's concurrency timestamp and image rows. Any other changed field
 * can represent a remote edit and must keep the queued update's original precondition.
 */
internal fun ProjectWriteRequest.rebaseExpectedUpdatedAtAfterMediaOrder(
    before: ProjectDto,
    after: ProjectDto,
): ProjectWriteRequest? =
    takeIf {
            expectedUpdatedAt == before.updatedAt &&
                before.copy(updatedAt = after.updatedAt, images = after.images) == after
        }
        ?.copy(expectedUpdatedAt = after.updatedAt)
