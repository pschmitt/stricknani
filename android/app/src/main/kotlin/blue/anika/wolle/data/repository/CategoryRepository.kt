package blue.anika.wolle.data.repository

import blue.anika.wolle.data.api.SyncApi
import blue.anika.wolle.data.db.dao.CategoryDao
import blue.anika.wolle.data.db.dao.SyncStateDao
import blue.anika.wolle.data.db.entity.CategoryEntity
import blue.anika.wolle.data.db.entity.SyncStateEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

private const val ENTITY_TYPE = "category"

/**
 * Cache-first access to categories. Sync always replaces the whole cached list - see
 * `sync_categories`'s docstring (`stricknani/routes/api/sync.py`) for why there's no delta here.
 */
@Singleton
class CategoryRepository
@Inject
constructor(
    private val categoryDao: CategoryDao,
    private val syncStateDao: SyncStateDao,
    private val syncApi: SyncApi,
) {
    fun observeAll(): Flow<List<CategoryEntity>> = categoryDao.observeAll()

    suspend fun sync() {
        val cursor = syncStateDao.getCursor(ENTITY_TYPE)
        val response = syncApi.syncCategories(since = cursor)
        categoryDao.replaceAll(response.updated.map { CategoryEntity(id = it.id, name = it.name) })
        syncStateDao.setCursor(SyncStateEntity(ENTITY_TYPE, response.serverTime))
    }
}
