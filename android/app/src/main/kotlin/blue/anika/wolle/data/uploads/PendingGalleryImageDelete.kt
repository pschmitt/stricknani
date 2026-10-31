package blue.anika.wolle.data.uploads

import kotlinx.serialization.Serializable

/** Payload for a queued deletion of an already-uploaded gallery image. */
@Serializable data class PendingGalleryImageDelete(val imageId: Int)
