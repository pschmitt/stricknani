package blue.anika.wolle.data.uploads

import kotlinx.serialization.Serializable

/**
 * Payload for a queued media reorder: the complete desired order of one group's server media ids.
 * [stepId] is set for a project step's images and null for a project's title images or a yarn's
 * photos (the mutation's operation says which).
 */
@Serializable data class PendingMediaOrder(val ids: List<Int>, val stepId: Int? = null)
