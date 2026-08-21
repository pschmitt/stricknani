package blue.anika.wolle.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A synced category. Categories have no `updated_at`/audit trail server-side (see
 * `stricknani/routes/api/sync.py`'s `sync_categories` docstring), so the repository replaces this
 * table's whole contents on every sync rather than upserting a delta.
 */
@Entity(tableName = "categories")
data class CategoryEntity(@PrimaryKey val id: Int, val name: String)
