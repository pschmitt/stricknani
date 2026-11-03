package blue.anika.wolle.data.repository

import blue.anika.wolle.data.api.ApiCapabilities
import blue.anika.wolle.data.api.CategoriesApi
import blue.anika.wolle.data.api.SyncApi
import blue.anika.wolle.data.api.dto.CategoryCreateRequest
import blue.anika.wolle.data.api.dto.CategoryDto
import blue.anika.wolle.data.api.dto.CategorySyncResponseDto
import blue.anika.wolle.data.api.dto.CategoryUpdateRequest
import blue.anika.wolle.data.api.dto.ProjectSyncResponseDto
import blue.anika.wolle.data.api.dto.YarnSyncResponseDto
import blue.anika.wolle.data.api.v2.CategoriesV2Api
import blue.anika.wolle.data.api.v2.MetaV2Api
import blue.anika.wolle.data.api.v2.MetaV2Dto
import blue.anika.wolle.data.api.v2.MetaV2UserDto
import blue.anika.wolle.data.api.v2.SyncV2Api
import blue.anika.wolle.data.api.v2.V2Envelope
import blue.anika.wolle.data.api.v2.V2SyncPageDto
import blue.anika.wolle.data.db.dao.CategoryDao
import blue.anika.wolle.data.db.dao.SyncStateDao
import blue.anika.wolle.data.db.entity.CategoryEntity
import blue.anika.wolle.data.db.entity.SyncStateEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/** v2 category delta sync: wire parsing, paging, deletes, full resync and the v1 fallback. */
class CategorySyncTest {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private fun http(status: Int) =
        HttpException(
            Response.error<Any>(
                status,
                // A v2 server always answers with an error envelope.
                """{"error":{"code":"error_$status","message":"x"}}"""
                    .toResponseBody("application/json".toMediaType()),
            )
        )

    private class FakeCategoryDao(initial: List<CategoryEntity> = emptyList()) : CategoryDao {
        val rows = MutableStateFlow(initial.sortedBy { it.name })
        var writes = 0

        override fun observeAll(): Flow<List<CategoryEntity>> = rows

        override suspend fun getAllOnce() = rows.value

        override suspend fun deleteAll() {
            writes++
            rows.value = emptyList()
        }

        override suspend fun upsertAll(categories: List<CategoryEntity>) {
            writes++
            val byId = rows.value.associateBy { it.id } + categories.associateBy { it.id }
            rows.value = byId.values.sortedBy { it.name }
        }

        override suspend fun deleteByIds(ids: List<Int>) {
            writes++
            rows.value = rows.value.filterNot { it.id in ids }
        }
    }

    private class FakeSyncState(var cursor: String? = null) : SyncStateDao {
        override suspend fun getCursor(entityType: String) = cursor

        override fun observeAll(): Flow<List<SyncStateEntity>> = MutableStateFlow(emptyList())

        override suspend fun setCursor(state: SyncStateEntity) {
            cursor = state.cursor
        }
    }

    private class FakeV1(var response: CategorySyncResponseDto? = null) : SyncApi {
        var calls = 0

        override suspend fun syncProjects(since: String?): ProjectSyncResponseDto = error("unused")

        override suspend fun syncYarns(since: String?): YarnSyncResponseDto = error("unused")

        override suspend fun syncCategories(since: String?): CategorySyncResponseDto {
            calls++
            return response!!
        }
    }

    private class FakeV2(val pages: ArrayDeque<() -> V2SyncPageDto<CategoryDto>>) : SyncV2Api {
        val requests = mutableListOf<Triple<String?, String?, Int?>>()

        override suspend fun syncProjects(since: String?, cursor: String?, limit: Int?) =
            error("unused")

        override suspend fun syncYarns(since: String?, cursor: String?, limit: Int?) =
            error("unused")

        override suspend fun syncCategories(since: String?, cursor: String?, limit: Int?) =
            V2Envelope(pages.removeFirst()().also { requests += Triple(since, cursor, limit) })
    }

    private val unusedCategoriesApi =
        object : CategoriesApi {
            override suspend fun listCategories() = emptyList<CategoryDto>()

            override suspend fun createCategory(request: CategoryCreateRequest) = error("unused")

            override suspend fun updateCategory(categoryId: Int, request: CategoryUpdateRequest) =
                error("unused")

            override suspend fun deleteCategory(categoryId: Int) = Unit
        }
    private val unusedCategoriesV2Api =
        object : CategoriesV2Api {
            override suspend fun listCategories() = V2Envelope(emptyList<CategoryDto>())

            override suspend fun createCategory(request: CategoryCreateRequest) = error("unused")

            override suspend fun updateCategory(categoryId: Int, request: CategoryUpdateRequest) =
                error("unused")

            override suspend fun deleteCategory(categoryId: Int) = Unit
        }

    private fun capabilities(v2: Boolean) =
        ApiCapabilities(
            object : MetaV2Api {
                override suspend fun getMeta() =
                    V2Envelope(
                        MetaV2Dto(
                            user =
                                if (v2) MetaV2UserDto(id = 1, email = "a@b.c", isAdmin = false)
                                else null
                        )
                    )
            }
        ) {
            "https://s\ntoken"
        }

    private fun repository(
        dao: FakeCategoryDao,
        state: FakeSyncState,
        v1: FakeV1,
        v2: FakeV2,
        v2Enabled: Boolean = true,
    ) =
        CategoryRepository(
            dao,
            state,
            v1,
            v2,
            unusedCategoriesApi,
            unusedCategoriesV2Api,
            capabilities(v2Enabled),
        )

    private fun page(
        updated: List<CategoryDto> = emptyList(),
        deleted: List<Int> = emptyList(),
        time: String = "T1",
        full: Boolean = false,
        hasMore: Boolean = false,
        next: String? = null,
    ) = V2SyncPageDto(updated, deleted, time, full, hasMore, next)

    private fun v2(vararg pages: () -> V2SyncPageDto<CategoryDto>) =
        FakeV2(ArrayDeque(pages.toList()))

    @Test
    fun `decodes the wrapped category sync body`() {
        val body =
            """{"data": {"updated": [{"id": 3, "name": "Socken"}], "deleted_ids": [8],
            "server_time": "2026-11-03T10:00:00.123456Z", "full_resync_required": false,
            "has_more": true, "next_cursor": "c_-1"}}"""
        val data = json.decodeFromString<V2Envelope<V2SyncPageDto<CategoryDto>>>(body).data

        assertEquals(listOf(CategoryDto(3, "Socken")), data.updated)
        assertEquals(listOf(8), data.deletedIds)
        assertEquals("2026-11-03T10:00:00.123456Z", data.serverTime)
        assertTrue(data.hasMore)
        assertEquals("c_-1", data.nextCursor)
    }

    @Test
    fun `v2 applies updates and tombstones, pages by cursor and stores the first server time`() =
        runTest {
            val dao = FakeCategoryDao(listOf(CategoryEntity(1, "Hats"), CategoryEntity(2, "Old")))
            val state = FakeSyncState("T0")
            val api =
                v2(
                    {
                        page(
                            listOf(CategoryDto(1, "Caps")),
                            time = "T1",
                            hasMore = true,
                            next = "c1",
                        )
                    },
                    { page(listOf(CategoryDto(3, "Socks")), deleted = listOf(2), time = "T1") },
                )

            val changed = repository(dao, state, FakeV1(), api).sync()

            assertTrue(changed)
            assertEquals(
                listOf(CategoryEntity(1, "Caps"), CategoryEntity(3, "Socks")),
                dao.rows.value,
            )
            assertEquals("T1", state.cursor)
            assertEquals(
                listOf(Triple<String?, String?, Int?>("T0", null, null), Triple(null, "c1", null)),
                api.requests,
            )
        }

    @Test
    fun `an unchanged delta writes nothing and reports no change`() = runTest {
        val dao = FakeCategoryDao(listOf(CategoryEntity(1, "Hats")))
        val state = FakeSyncState("T0")
        val api = v2({ page(listOf(CategoryDto(1, "Hats")), time = "T2") })

        assertFalse(repository(dao, state, FakeV1(), api).sync())
        assertEquals(0, dao.writes)
        assertEquals("T2", state.cursor)
    }

    @Test
    fun `a full resync drops categories the server no longer has`() = runTest {
        val dao = FakeCategoryDao(listOf(CategoryEntity(1, "Keep"), CategoryEntity(2, "Gone")))
        val state = FakeSyncState("stale")
        val api =
            v2(
                { page(full = true) },
                { page(listOf(CategoryDto(1, "Keep"), CategoryDto(4, "New")), time = "T5") },
            )

        assertTrue(repository(dao, state, FakeV1(), api).sync())
        assertEquals(listOf(CategoryEntity(1, "Keep"), CategoryEntity(4, "New")), dao.rows.value)
        assertEquals("T5", state.cursor)
    }

    @Test
    fun `an interrupted v2 run keeps the old cursor`() = runTest {
        val dao = FakeCategoryDao()
        val state = FakeSyncState("T0")
        val api =
            v2(
                { page(listOf(CategoryDto(1, "A")), hasMore = true, next = "c1") },
                { throw http(500) },
            )

        assertFailsWith<HttpException> { repository(dao, state, FakeV1(), api).sync() }
        assertEquals("T0", state.cursor)
    }

    @Test
    fun `404 and 405 from the v2 route fall back to the v1 full list`() = runTest {
        for (status in listOf(404, 405)) {
            val dao = FakeCategoryDao(listOf(CategoryEntity(9, "Stale")))
            val state = FakeSyncState("T0")
            val v1 =
                FakeV1(CategorySyncResponseDto(listOf(CategoryDto(1, "Hats")), emptyList(), "V1T"))
            val api = v2({ throw http(status) })

            assertTrue("HTTP $status", repository(dao, state, v1, api).sync())
            assertEquals(1, v1.calls)
            assertEquals(listOf(CategoryEntity(1, "Hats")), dao.rows.value)
            assertEquals("V1T", state.cursor)
        }
    }

    @Test
    fun `other v2 failures are not hidden by the fallback`() = runTest {
        val v1 = FakeV1(CategorySyncResponseDto(emptyList(), emptyList(), "V1T"))
        val api = v2({ throw http(401) })

        assertFailsWith<HttpException> {
            repository(FakeCategoryDao(), FakeSyncState(), v1, api).sync()
        }
        assertEquals(0, v1.calls)
    }

    @Test
    fun `a server without v2 uses v1 and never calls the v2 route`() = runTest {
        val dao = FakeCategoryDao()
        val v1 = FakeV1(CategorySyncResponseDto(listOf(CategoryDto(1, "Hats")), emptyList(), "V1T"))
        val api = v2()

        assertTrue(repository(dao, FakeSyncState(), v1, api, v2Enabled = false).sync())
        assertEquals(1, v1.calls)
        assertTrue(api.requests.isEmpty())
    }

    @Test
    fun `only new or renamed categories are upserted`() {
        val local = listOf(CategoryEntity(1, "A"), CategoryEntity(2, "B"))
        val delivered = listOf(CategoryDto(1, "A"), CategoryDto(2, "B2"), CategoryDto(3, "C"))

        assertEquals(
            listOf(CategoryEntity(2, "B2"), CategoryEntity(3, "C")),
            categoriesToUpsert(local, delivered),
        )
    }
}

private suspend inline fun <reified T : Throwable> assertFailsWith(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: Throwable) {
        if (e is T) return
        throw e
    }
    fail("Expected ${T::class.java.simpleName}")
}
