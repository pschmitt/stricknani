package blue.anika.wolle.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-entity-type delta-sync cursor: the previous sync's `server_time`, stored and replayed
 * verbatim as the next request's `since` (never reformatted client-side - see `SyncApi`'s kdoc for
 * why). Kept in Room rather than DataStore so it commits atomically alongside the row
 * upserts/deletes it gates in the same sync pass.
 */
@Entity(tableName = "sync_state")
data class SyncStateEntity(@PrimaryKey val entityType: String, val cursor: String)
