package blue.anika.wolle.ui.home

import blue.anika.wolle.data.api.dto.ActivityItemDto
import blue.anika.wolle.data.api.dto.ActivityMetaDto
import blue.anika.wolle.data.api.dto.ActivityPageDto
import blue.anika.wolle.data.repository.HistoryResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ActivityControllerTest {
    private fun item(id: Long) =
        ActivityItemDto(
            id = id,
            createdAt = "2026-11-03T10:09:12.000000Z",
            action = "updated",
            entityType = "project",
            entityId = 1,
        )

    private fun page(ids: List<Long>, hasMore: Boolean) =
        ActivityPageDto(
            data = ids.map(::item),
            meta = ActivityMetaDto(limit = 2, hasMore = hasMore, nextBefore = ids.lastOrNull()),
        )

    private class Fake(var results: ArrayDeque<HistoryResult<ActivityPageDto>>) {
        val calls = mutableListOf<Pair<Int, Long?>>()

        suspend fun fetch(limit: Int, before: Long?): HistoryResult<ActivityPageDto> {
            calls += limit to before
            return results.removeFirst()
        }
    }

    private fun TestScope.controller(fake: Fake) =
        ActivityController(
            scope = this,
            fetch = fake::fetch,
            pageSize = 2,
        )

    @Test
    fun `first page loads, show more appends the next page with the cursor`() = runTest {
        val fake =
            Fake(
                ArrayDeque(
                    listOf(
                        HistoryResult.Ok(page(listOf(9, 8), hasMore = true)),
                        HistoryResult.Ok(page(listOf(8, 7), hasMore = false)),
                    )
                )
            )
        val controller = controller(fake)
        assertEquals(ActivityPhase.Idle, controller.state.value.phase)
        controller.reload()
        assertEquals(ActivityPhase.Loading, controller.state.value.phase)
        advanceUntilIdle()
        assertEquals(ActivityPhase.Ready, controller.state.value.phase)
        assertTrue(controller.state.value.hasMore)

        controller.loadMore()
        assertTrue(controller.state.value.loadingMore)
        advanceUntilIdle()
        assertEquals(listOf(9L, 8L, 7L), controller.state.value.items.map { it.id })
        assertFalse(controller.state.value.hasMore)
        assertEquals(listOf(2 to null, 2 to 8L), fake.calls)

        controller.loadMore() // nothing more: no request
        advanceUntilIdle()
        assertEquals(2, fake.calls.size)
    }

    @Test
    fun `offline with nothing shown becomes the quiet note`() = runTest {
        val controller = controller(Fake(ArrayDeque(listOf(HistoryResult.Failed(offline = true)))))
        controller.reload()
        advanceUntilIdle()
        assertEquals(ActivityPhase.Unavailable, controller.state.value.phase)
    }

    @Test
    fun `a failed refresh keeps what is on screen`() = runTest {
        val controller =
            controller(
                Fake(
                    ArrayDeque(
                        listOf(
                            HistoryResult.Ok(page(listOf(3), hasMore = false)),
                            HistoryResult.Failed(offline = true),
                        )
                    )
                )
            )
        controller.reload()
        advanceUntilIdle()
        controller.reload()
        advanceUntilIdle()
        assertEquals(ActivityPhase.Ready, controller.state.value.phase)
        assertEquals(listOf(3L), controller.state.value.items.map { it.id })
    }

    @Test
    fun `a failed show more keeps the list and flags it`() = runTest {
        val controller =
            controller(
                Fake(
                    ArrayDeque(
                        listOf(
                            HistoryResult.Ok(page(listOf(3, 2), hasMore = true)),
                            HistoryResult.Failed(offline = false),
                        )
                    )
                )
            )
        controller.reload()
        advanceUntilIdle()
        controller.loadMore()
        advanceUntilIdle()
        val state = controller.state.value
        assertEquals(2, state.items.size)
        assertTrue(state.moreFailed)
        assertFalse(state.loadingMore)
        assertTrue(state.hasMore)
    }

    @Test
    fun `a server without the endpoint hides the section, as does signing out`() = runTest {
        val controller = controller(Fake(ArrayDeque(listOf(HistoryResult.Unsupported))))
        controller.reload()
        advanceUntilIdle()
        assertEquals(ActivityPhase.Hidden, controller.state.value.phase)

        val signedOut =
            controller(Fake(ArrayDeque(listOf(HistoryResult.Ok(page(listOf(1), false))))))
        signedOut.reload()
        advanceUntilIdle()
        signedOut.hide()
        assertEquals(ActivityPhase.Hidden, signedOut.state.value.phase)
        assertTrue(signedOut.state.value.items.isEmpty())
    }

    @Test
    fun `the full page asks for its own larger page size`() = runTest {
        val fake = Fake(ArrayDeque(listOf(HistoryResult.Ok(page(listOf(1), hasMore = false)))))
        val controller = ActivityController(this, fake::fetch, pageSize = ACTIVITY_FULL_PAGE_SIZE)
        controller.reload()
        advanceUntilIdle()
        assertEquals(listOf(ACTIVITY_FULL_PAGE_SIZE to null), fake.calls)
        assertTrue(ACTIVITY_FULL_PAGE_SIZE > ACTIVITY_PAGE_SIZE)
    }
}
