package blue.anika.wolle.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A synced yarn. See [ProjectEntity]'s kdoc for the flat-columns-plus-detailJson rationale. */
@Entity(tableName = "yarns")
data class YarnEntity(
    @PrimaryKey val id: Int,
    val name: String,
    val brand: String?,
    val colorway: String?,
    val weightCategory: String?,
    val isFavorite: Boolean,
    /** Epoch millis, parsed from the wire `updated_at` via `DateTimeUtils`. */
    val updatedAt: Long,
    val previewUrl: String?,
    val detailJson: String,
)
