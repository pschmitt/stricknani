package blue.anika.wolle.data.uploads

import kotlinx.serialization.Serializable

/** Payload for a queued deletion of an already-uploaded project attachment. */
@Serializable
data class PendingAttachmentDelete(val attachmentId: Int)
