package blue.anika.wolle.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `stricknani/routes/api/schemas.py`'s `CategorySyncResponse`. Categories aren't audited server-
 * side, so `updated` is always a full list and `deletedIds` is always empty - deletions are
 * inferred client-side by diffing against what's already cached.
 */
@Serializable
data class CategorySyncResponseDto(
    val updated: List<CategoryDto>,
    @SerialName("deleted_ids") val deletedIds: List<Int>,
    @SerialName("server_time") val serverTime: String,
)
