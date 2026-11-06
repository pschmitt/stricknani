package blue.anika.wolle.ui.common

import blue.anika.wolle.R
import blue.anika.wolle.data.api.ApiCapabilities
import blue.anika.wolle.data.api.v2.ArchiveRequestBody
import blue.anika.wolle.data.api.v2.ArchiveStatusDto
import blue.anika.wolle.data.api.v2.ArchiveV2Api
import blue.anika.wolle.data.api.v2.MetaV2Api
import blue.anika.wolle.data.api.v2.MetaV2Dto
import blue.anika.wolle.data.api.v2.MetaV2UserDto
import blue.anika.wolle.data.api.v2.V2Envelope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

private class FakeArchiveApi(
    var statuses: ArrayDeque<String?>,
    var failRequest: HttpException? = null,
) : ArchiveV2Api {
    val calls = mutableListOf<String>()

    override suspend fun status(entity: String, id: Int): V2Envelope<ArchiveStatusDto> {
        calls += "GET $entity/$id"
        val state = if (statuses.size > 1) statuses.removeFirst() else statuses.first()
        return V2Envelope(ArchiveStatusDto(state, if (state == null) null else 1))
    }

    override suspend fun request(
        entity: String,
        id: Int,
        body: ArchiveRequestBody,
    ): V2Envelope<ArchiveStatusDto> {
        calls += "POST $entity/$id"
        failRequest?.let { throw it }
        return V2Envelope(ArchiveStatusDto("pending", 1))
    }
}

private fun capabilities(archive: Boolean) =
    ApiCapabilities(
        object : MetaV2Api {
            override suspend fun getMeta(): V2Envelope<MetaV2Dto> =
                V2Envelope(MetaV2Dto(user = MetaV2UserDto(1, "a@b.c"), archive = archive))
        }
    ) {
        "s\nt"
    }

@OptIn(ExperimentalCoroutinesApi::class)
class ArchiveViewModelTest {
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a server without archiving offers nothing and is never asked`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val api = FakeArchiveApi(ArrayDeque(listOf(null)))
        val viewModel = ArchiveViewModel(api, capabilities(archive = false))
        viewModel.bind(ArchiveEntity.PROJECT, 7, "https://example.test/pattern")
        advanceUntilIdle()
        assertFalse(viewModel.state.value.available)
        assertEquals(emptyList<String>(), api.calls)
    }

    @Test
    fun `requesting polls until the copy is ready, then asks for a reload once`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val api = FakeArchiveApi(ArrayDeque(listOf(null, "running", "archived")))
        val viewModel = ArchiveViewModel(api, capabilities(archive = true))
        viewModel.bind(ArchiveEntity.YARN, 3, "https://example.test/yarn")
        advanceUntilIdle()
        assertNull(viewModel.state.value.state)
        assertEquals(0, viewModel.archivedEvents.value)

        viewModel.request()
        advanceUntilIdle()

        assertEquals("archived", viewModel.state.value.state)
        assertEquals(1, viewModel.archivedEvents.value)
        assertEquals(
            listOf("GET yarns/3", "POST yarns/3", "GET yarns/3", "GET yarns/3"),
            api.calls,
        )
    }

    @Test
    fun `an already archived source does not trigger a reload`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val viewModel =
            ArchiveViewModel(FakeArchiveApi(ArrayDeque(listOf("archived"))), capabilities(true))
        viewModel.bind(ArchiveEntity.PROJECT, 1, "https://example.test/p")
        advanceUntilIdle()
        assertEquals("archived", viewModel.state.value.state)
        assertEquals(0, viewModel.archivedEvents.value)
    }

    @Test
    fun `a rate-limited request says so`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val limited =
            HttpException(
                Response.error<Any>(
                    429,
                    """{"error":{"code":"rate_limited","message":"Slow down"}}""".toResponseBody(),
                )
            )
        val api = FakeArchiveApi(ArrayDeque(listOf(null)), failRequest = limited)
        val viewModel = ArchiveViewModel(api, capabilities(archive = true))
        viewModel.bind(ArchiveEntity.PROJECT, 2, "https://example.test/p")
        advanceUntilIdle()
        viewModel.request()
        advanceUntilIdle()
        assertEquals(R.string.archive_rate_limited, viewModel.state.value.error)
        assertFalse(viewModel.state.value.busy)
    }
}
