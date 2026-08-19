package blue.anika.wolle.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import blue.anika.wolle.data.db.entity.CategoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY name") fun observeAll(): Flow<List<CategoryEntity>>

    /** One-shot read (SNA-36) - lets [replaceAll]'s caller skip a no-op write. */
    @Query("SELECT * FROM categories ORDER BY name") suspend fun getAllOnce(): List<CategoryEntity>

    @Query("DELETE FROM categories") suspend fun deleteAll()

    @Upsert suspend fun upsertAll(categories: List<CategoryEntity>)

    /** Categories always sync as a full list - replace the table's whole contents atomically. */
    @Transaction
    suspend fun replaceAll(categories: List<CategoryEntity>) {
        deleteAll()
        upsertAll(categories)
    }
}
