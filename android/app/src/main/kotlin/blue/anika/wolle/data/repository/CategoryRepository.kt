package blue.anika.wolle.data.repository

import blue.anika.wolle.data.api.ApiCapabilities
import blue.anika.wolle.data.api.CategoriesApi
import blue.anika.wolle.data.api.SyncApi
import blue.anika.wolle.data.api.dto.CategoryCreateRequest
import blue.anika.wolle.data.api.dto.CategoryUpdateRequest
import blue.anika.wolle.data.api.v2.CategoriesV2Api
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
    private val categoriesApi: CategoriesApi,
    private val categoriesV2Api: CategoriesV2Api,
    private val capabilities: ApiCapabilities,
) {
    fun observeAll(): Flow<List<CategoryEntity>> = categoryDao.observeAll()

    /** Creates a category on the active API generation. Callers re-sync afterwards. */
    suspend fun create(name: String) {
        if (capabilities.useV2()) categoriesV2Api.createCategory(CategoryCreateRequest(name))
        else categoriesApi.createCategory(CategoryCreateRequest(name))
    }

    suspend fun rename(id: Int, name: String) {
        if (capabilities.useV2()) categoriesV2Api.updateCategory(id, CategoryUpdateRequest(name))
        else categoriesApi.updateCategory(id, CategoryUpdateRequest(name))
    }

    suspend fun delete(id: Int) {
        if (capabilities.useV2()) categoriesV2Api.deleteCategory(id)
        else categoriesApi.deleteCategory(id)
    }

    /**
     * Refreshes the complete category cache and reports whether its contents changed.
     *
     * Categories have no server-side delta cursor, so this remains a full replacement while
     * exposing the result needed by foreground refresh feedback.
     */
    suspend fun sync(): Boolean {
        val cursor = syncStateDao.getCursor(ENTITY_TYPE)
        val response = syncApi.syncCategories(since = cursor)
        val updated = response.updated.map { CategoryEntity(id = it.id, name = it.name) }
        // SNA-36: replaceAll's delete+upsert invalidates Room's observeAll() flow (whole-table
        // invalidation, not per-row) even when nothing changed, forcing every screen showing
        // category chips to recompose on every sync tick - skip the write when nothing moved.
        val changed = categoryDao.getAllOnce().toSet() != updated.toSet()
        if (changed) {
            categoryDao.replaceAll(updated)
        }
        syncStateDao.setCursor(SyncStateEntity(ENTITY_TYPE, response.serverTime))
        return changed
    }
}
