package blue.anika.wolle.data.repository

import blue.anika.wolle.data.api.ApiCapabilities
import blue.anika.wolle.data.api.CategoriesApi
import blue.anika.wolle.data.api.SyncApi
import blue.anika.wolle.data.api.dto.CategoryCreateRequest
import blue.anika.wolle.data.api.dto.CategoryDto
import blue.anika.wolle.data.api.dto.CategoryUpdateRequest
import blue.anika.wolle.data.api.v2.CategoriesV2Api
import blue.anika.wolle.data.api.v2.SyncV2Api
import blue.anika.wolle.data.api.v2.runV2Sync
import blue.anika.wolle.data.api.v2.staleAfterFullResync
import blue.anika.wolle.data.db.dao.CategoryDao
import blue.anika.wolle.data.db.dao.SyncStateDao
import blue.anika.wolle.data.db.entity.CategoryEntity
import blue.anika.wolle.data.db.entity.SyncStateEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import retrofit2.HttpException

private const val ENTITY_TYPE = "category"

/**
 * Cache-first access to categories. v2 syncs a delta; v1 (`sync_categories`'s docstring in
 * `stricknani/routes/api/sync.py`) always replaces the whole cached list.
 */
@Singleton
class CategoryRepository
@Inject
constructor(
    private val categoryDao: CategoryDao,
    private val syncStateDao: SyncStateDao,
    private val syncApi: SyncApi,
    private val syncV2Api: SyncV2Api,
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
     * Refreshes the category cache and reports whether its contents changed: a cursor-paged delta
     * from `/api/v2/sync/categories`, or the v1 full list on servers without v2 (or without that
     * route: 404/405 falls back to v1).
     */
    suspend fun sync(): Boolean {
        if (!capabilities.useV2()) return syncV1()
        return try {
            syncV2()
        } catch (failure: HttpException) {
            if (failure.code() != 404 && failure.code() != 405) throw failure
            syncV1()
        }
    }

    private suspend fun syncV1(): Boolean {
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

    /**
     * Delta semantics as for projects and yarns (see `runV2Sync`): the new cursor is stored only
     * after every page was applied, and `full_resync_required` drops what the server no longer has.
     * Categories have no queued local writes, so nothing is protected from those deletes.
     */
    private suspend fun syncV2(): Boolean {
        var changed = false
        val outcome =
            runV2Sync(
                since = syncStateDao.getCursor(ENTITY_TYPE),
                idOf = { it.id },
                fetch = { since, cursor ->
                    syncV2Api.syncCategories(since = since, cursor = cursor).data
                },
                onPage = { updated, deletedIds ->
                    val upserts = categoriesToUpsert(categoryDao.getAllOnce(), updated)
                    if (upserts.isNotEmpty()) {
                        categoryDao.upsertAll(upserts)
                        changed = true
                    }
                    if (deletedIds.isNotEmpty()) {
                        categoryDao.deleteByIds(deletedIds)
                        changed = true
                    }
                },
            )
        if (outcome.fullResync) {
            val stale =
                staleAfterFullResync(
                    localIds = categoryDao.getAllOnce().map { it.id },
                    seen = outcome.seenIds,
                    pendingLocalIds = emptySet(),
                )
            if (stale.isNotEmpty()) {
                categoryDao.deleteByIds(stale)
                changed = true
            }
        }
        syncStateDao.setCursor(SyncStateEntity(ENTITY_TYPE, outcome.serverTime))
        return changed
    }
}

/** The delivered categories that are new or renamed locally (SNA-36: skip no-op writes). */
internal fun categoriesToUpsert(
    local: List<CategoryEntity>,
    delivered: List<CategoryDto>,
): List<CategoryEntity> {
    val byId = local.associateBy { it.id }
    return delivered.map { CategoryEntity(it.id, it.name) }.filter { byId[it.id] != it }
}
