package blue.anika.wolle.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A synced project. `category`/`tags`/`isFavorite`/`updatedAt`/`previewUrl` are flattened out of
 * [detailJson] purely so list/filter/sort queries don't need to parse JSON per row; `detailJson`
 * is the full `ProjectDto` payload (verbatim from a sync's `updated[]` entry), decoded at read
 * time by the repository via `Json.decodeFromString<ProjectDto>(detailJson)` - not deep-normalized
 * into further Room tables/columns, same pattern as syncwich's `RecipeDetailEntity`.
 */
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: Int,
    val name: String,
    val category: String?,
    /** JSON-encoded `List<String>` - small enough per project that in-memory filtering after decode beats a join table. */
    val tagsJson: String,
    val isFavorite: Boolean,
    /** Epoch millis, parsed from the wire `updated_at` via `DateTimeUtils`. */
    val updatedAt: Long,
    val previewUrl: String?,
    val detailJson: String,
)
