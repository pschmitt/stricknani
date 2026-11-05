package blue.anika.wolle.data.db.entity

import androidx.room.ColumnInfo
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
    /** See `ProjectEntity.ownerId`. */
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
