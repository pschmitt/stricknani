package blue.anika.wolle.data.imports

import blue.anika.wolle.data.api.dto.ImportAssetDto
import blue.anika.wolle.data.api.dto.ProjectWriteRequest
import blue.anika.wolle.data.repository.ImportDetail
import blue.anika.wolle.data.repository.ImportDetailKind
import blue.anika.wolle.data.repository.ImportedProjectPreview
import blue.anika.wolle.data.repository.ImportedYarn
import java.io.IOException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class PendingImportTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun `a finished preview survives the file round trip`() {
        val ready: PendingImport =
            PendingImport.ProjectReady(
                url = "https://e.com/p",
                preview =
                    ImportedProjectPreview(
                        sourceUrl = "https://e.com/p",
                        request = ProjectWriteRequest(name = "Little Pearl Hat"),
                        imageUrls = listOf("https://e.com/1.jpg"),
                        assets = listOf(ImportAssetDto(url = "https://e.com/chart.jpg")),
                        aiFallback = false,
                        details = listOf(ImportDetail(ImportDetailKind.Sizes, "0-4 y")),
                        yarns = listOf(ImportedYarn(name = "Baby Merino", brand = "DROPS")),
                    ),
            )
        val text = json.encodeToString(PendingImport.serializer(), ready)
        assertEquals(ready, json.decodeFromString(PendingImport.serializer(), text))
    }

    @Test
    fun `a stored failure is raised as the importer raised it`() {
        val body = """{"error":{"code":"import_blocked","message":"x"}}"""
        val failure =
            HttpException(
                Response.error<Any>(502, body.toResponseBody("application/json".toMediaType()))
            )
        val stored =
            json.decodeFromString(
                PendingImport.serializer(),
                json.encodeToString(
                    PendingImport.serializer(),
                    PendingImport.Failed.of("https://e.com/p", failure),
                ),
            ) as PendingImport.Failed
        val raised = stored.toThrowable() as HttpException
        assertEquals(502, raised.code())
        assertEquals(body, raised.response()?.errorBody()?.string())
        assertTrue(
            PendingImport.Failed.of("u", IOException("offline")).toThrowable() is IOException
        )
    }
}
