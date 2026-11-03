package blue.anika.wolle.data.repository

import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class ActivityRepositoryTest {
    @Test
    fun `404 means the server has no feed yet, offline is a quiet failure`() {
        val notFound =
            HttpException(
                Response.error<Any>(404, "{}".toResponseBody("application/json".toMediaType()))
            )
        assertEquals(HistoryResult.Unsupported, notFound.toHistoryFailure())
        assertEquals(HistoryResult.Failed(offline = true), IOException("down").toHistoryFailure())
    }
}
