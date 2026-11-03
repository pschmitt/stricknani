package blue.anika.wolle.data.repository

import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class HistoryRepositoryTest {
    private fun http(code: Int) =
        HttpException(
            Response.error<Any>(code, "{}".toResponseBody("application/json".toMediaType()))
        )

    @Test
    fun `a server without the endpoint hides the section`() {
        assertEquals(HistoryResult.Unsupported, http(404).toHistoryFailure())
        assertEquals(HistoryResult.Unsupported, http(405).toHistoryFailure())
    }

    @Test
    fun `network and server errors are failures, not hidden`() {
        assertEquals(HistoryResult.Failed(offline = true), IOException("down").toHistoryFailure())
        assertEquals(
            HistoryResult.Failed(offline = true),
            RuntimeException("wrapped", IOException("down")).toHistoryFailure(),
        )
        assertEquals(HistoryResult.Failed(offline = false), http(500).toHistoryFailure())
        assertTrue(http(403).toHistoryFailure() is HistoryResult.Failed)
    }
}
