package blue.anika.wolle.data.repository

import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/** Opening an item by id must not depend on the Mine | Everyone list filter. */
class ItemLoadTest {
    private fun http(code: Int) =
        HttpException(
            Response.error<Any>(code, "{}".toResponseBody("application/json".toMediaType()))
        )

    private suspend fun load(
        cached: Boolean = false,
        deletedLocally: Boolean = false,
        id: Int = 5,
        fetch: suspend () -> Unit,
    ): ItemLoad =
        loadIfMissing(
            id = id,
            isCached = { cached },
            isDeletedLocally = { deletedLocally },
            fetchAndCache = fetch,
        )

    @Test
    fun `a cached item opens without any request`() = runTest {
        var fetched = false

        val result = load(cached = true) { fetched = true }

        assertEquals(ItemLoad.Available, result)
        assertFalse(fetched)
    }

    @Test
    fun `a cache miss fetches the item from the server`() = runTest {
        var fetched = false

        val result = load { fetched = true }

        assertEquals(ItemLoad.Available, result)
        assertTrue(fetched)
    }

    @Test
    fun `an offline miss is not available offline`() = runTest {
        assertEquals(ItemLoad.Offline, load { throw IOException("no route") })
    }

    @Test
    fun `a real 404 is gone`() = runTest {
        assertEquals(ItemLoad.Gone, load { throw http(404) })
    }

    @Test
    fun `other server failures are retryable failures`() = runTest {
        assertEquals(ItemLoad.Failed(403), load { throw http(403) })
        assertEquals(ItemLoad.Failed(503), load { throw http(503) })
    }

    @Test
    fun `an item queued for deletion is never fetched back`() = runTest {
        var fetched = false

        assertEquals(ItemLoad.Gone, load(deletedLocally = true) { fetched = true })
        assertFalse(fetched)
    }

    @Test
    fun `offline placeholder ids are never fetched`() = runTest {
        var fetched = false

        assertEquals(ItemLoad.Gone, load(id = -3) { fetched = true })
        assertFalse(fetched)
    }
}
