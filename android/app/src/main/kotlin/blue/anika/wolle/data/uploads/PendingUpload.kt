package blue.anika.wolle.data.uploads

import kotlinx.serialization.Serializable

/** A copied local file that can safely outlive the picker activity and an offline edit. */
@Serializable
data class PendingUpload(
    val path: String,
    val fileName: String,
    val contentType: String = "application/octet-stream",
    val altText: String = "",
    val stepIndex: Int? = null,
)
