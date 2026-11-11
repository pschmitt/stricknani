package blue.anika.wolle.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * A synced project. `category`/`tags`/`isFavorite`/`updatedAt`/`previewUrl` are flattened out of
 * [detailJson] purely so list/filter/sort queries don't need to parse JSON per row; `detailJson` is
 * the full `ProjectDto` payload (verbatim from a sync's `updated[]` entry), decoded at read time by
 * the repository via `Json.decodeFromString<ProjectDto>(detailJson)` - not deep-normalized into
 * further Room tables/columns, same pattern as syncwich's `RecipeDetailEntity`.
 */
@Serializable
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: Int,
    val name: String,
    val category: String?,
    /**
     * JSON-encoded `List<String>` - small enough per project that in-memory filtering after decode
     * beats a join table.
     */
    val tagsJson: String,
    val isFavorite: Boolean,
    /**
     * `in_progress` or `done`, flattened out of [detailJson] so the status filter and the card
     * badge need no JSON decode per row.
     */
    @ColumnInfo(defaultValue = "'in_progress'") val status: String = "in_progress",
    /** Epoch millis, parsed from the wire `updated_at` via `DateTimeUtils`. */
    val updatedAt: Long,
    val previewUrl: String?,
    val detailJson: String,
    /**
     * Owner of the project as the server reported it (admin scope). Null for rows without owner
     * info, which are the signed-in user's own. A row is "foreign" when [ownerId] is set and
     * differs from the signed-in user.
     */
    val ownerId: Long? = null,
    val ownerName: String? = null,
    val ownerAvatarUrl: String? = null,
    /**
     * Sharing: the caller's access as the server reported it (`access.role`). Null = the signed-in
     * user's own item (and rows from before sharing). Shared rows carry `viewer`/`editor`; an
     * administrator's view of someone else's item carries `admin`.
     */
    val accessRole: String? = null,
    /** True for a yarn read only through a shared project. */
    @ColumnInfo(defaultValue = "0") val accessDerived: Boolean = false,
    /** `access.can_edit`; null = not reported (treated as the owner's full access). */
    val canEdit: Boolean? = null,
    /** `access.can_manage`: may share, transfer and delete; null = not reported. */
    val canManage: Boolean? = null,
)
