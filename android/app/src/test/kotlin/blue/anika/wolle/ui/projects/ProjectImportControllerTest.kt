package blue.anika.wolle.ui.projects

import blue.anika.wolle.data.api.dto.ProjectWriteRequest
import blue.anika.wolle.data.repository.ImportedProjectPreview
import blue.anika.wolle.data.repository.ProjectImporter
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectImportControllerTest {
    @Test
    fun `import completes only after explicit confirmation`() = runTest {
        val preview = preview()
        var saved: ImportedProjectPreview? = null
        val controller =
            ProjectImportController(
                scope = this,
                importer = FakeImporter { preview },
                save = {
                    saved = it
                    42
                },
            )

        controller.start("https://example.com/pattern")
        assertTrue(controller.state.value is ProjectImportState.Importing)
        advanceUntilIdle()
        assertEquals(ProjectImportState.AwaitingConfirmation(preview), controller.state.value)
        assertEquals(null, saved)

        controller.confirm()
        assertTrue(controller.state.value is ProjectImportState.Saving)
        advanceUntilIdle()

        assertEquals(ProjectImportState.Completed(42, preview), controller.state.value)
        assertEquals(preview, saved)
    }

    @Test
    fun `confirming with a photo selection saves only those photos`() = runTest {
        val preview = preview()
        var saved: ImportedProjectPreview? = null
        val controller =
            ProjectImportController(
                scope = this,
                importer = FakeImporter { preview },
                save = {
                    saved = it
                    7
                },
            )
        controller.start("https://example.com/pattern")
        advanceUntilIdle()

        controller.confirm(selectedImages = listOf("https://example.com/b.jpg"))
        advanceUntilIdle()

        assertEquals(listOf("https://example.com/b.jpg"), saved?.request?.imageUrls)
    }

    @Test
    fun `confirming saves the reviewed yarns and notes instead of the imported request`() =
        runTest {
            val preview = preview()
            var saved: ImportedProjectPreview? = null
            val controller =
                ProjectImportController(
                    scope = this,
                    importer = FakeImporter { preview },
                    save = {
                        saved = it
                        7
                    },
                )
            controller.start("https://example.com/pattern")
            advanceUntilIdle()

            controller.confirm(
                selectedImages = listOf("https://example.com/b.jpg"),
                reviewed = preview.request.copy(yarnIds = listOf(3), notes = "Reviewed notes"),
            )
            advanceUntilIdle()

            assertEquals(listOf(3), saved?.request?.yarnIds)
            assertEquals("Reviewed notes", saved?.request?.notes)
            assertEquals(listOf("https://example.com/b.jpg"), saved?.request?.imageUrls)
        }

    @Test
    fun `cancelling an in-flight import returns to idle and never saves`() = runTest {
        val request = CompletableDeferred<Unit>()
        var saveCalls = 0
        val controller =
            ProjectImportController(
                scope = this,
                importer =
                    FakeImporter {
                        request.await()
                        preview()
                    },
                save = {
                    saveCalls++
                    1
                },
            )

        controller.start("https://example.com/pattern")
        advanceUntilIdle()
        assertTrue(controller.state.value is ProjectImportState.Importing)

        controller.cancel()
        assertEquals(ProjectImportState.Idle, controller.state.value)
        request.complete(Unit)
        advanceUntilIdle()

        assertEquals(ProjectImportState.Idle, controller.state.value)
        assertEquals(0, saveCalls)
    }

    @Test
    fun `offline failure is retained for retry`() = runTest {
        val controller =
            ProjectImportController(
                scope = this,
                importer = FakeImporter { throw IOException("offline") },
                save = { 1 },
            )

        controller.start("https://example.com/pattern")
        advanceUntilIdle()

        assertEquals(
            ProjectImportState.Failed("https://example.com/pattern", ProjectImportFailure.Offline),
            controller.state.value,
        )
    }

    private fun http(status: Int, body: String) =
        HttpException(
            Response.error<Any>(status, body.toResponseBody("application/json".toMediaType()))
        )

    private suspend fun kotlinx.coroutines.test.TestScope.failureOf(
        failure: Throwable
    ): ProjectImportFailure {
        val controller =
            ProjectImportController(
                scope = this,
                importer = FakeImporter { throw failure },
                save = { 1 },
            )
        controller.start("https://example.com/pattern")
        advanceUntilIdle()
        return (controller.state.value as ProjectImportState.Failed).reason
    }

    @Test
    fun `v2 import errors map to the friendly messages`() = runTest {
        fun body(code: String) = """{"error":{"code":"$code","message":"m"}}"""
        assertEquals(
            ProjectImportFailure.RateLimited,
            failureOf(http(429, body("rate_limited"))),
        )
        assertEquals(
            ProjectImportFailure.InvalidUrl,
            failureOf(http(422, body("validation_error"))),
        )
        assertEquals(ProjectImportFailure.InvalidUrl, failureOf(http(422, body("unsafe_url"))))
        assertEquals(
            ProjectImportFailure.Server,
            failureOf(http(503, body("import_unavailable"))),
        )
        assertEquals(
            ProjectImportFailure.Disabled,
            failureOf(http(503, body("feature_disabled"))),
        )
        assertEquals(
            ProjectImportFailure.Blocked,
            failureOf(http(502, body("import_blocked"))),
        )
        assertEquals(
            ProjectImportFailure.NotFound,
            failureOf(http(502, body("import_not_found"))),
        )
        assertEquals(
            ProjectImportFailure.Server,
            failureOf(http(504, body("import_timeout"))),
        )
        assertEquals(
            ProjectImportFailure.Server,
            failureOf(http(422, body("unsupported_document"))),
        )
        assertEquals(ProjectImportFailure.Server, failureOf(http(502, "not json")))
    }

    @Test
    fun `invalid URL fails before a network request starts`() = runTest {
        var calls = 0
        val controller =
            ProjectImportController(
                scope = this,
                importer =
                    FakeImporter {
                        calls++
                        preview()
                    },
                save = { 1 },
            )

        controller.start("not a URL")

        assertEquals(
            ProjectImportState.Failed("not a URL", ProjectImportFailure.InvalidUrl),
            controller.state.value,
        )
        assertEquals(0, calls)
    }

    private class FakeImporter(private val block: suspend () -> ImportedProjectPreview) :
        ProjectImporter {
        override suspend fun importFromUrl(url: String): ImportedProjectPreview = block()
    }

    private fun preview() =
        ImportedProjectPreview(
            sourceUrl = "https://example.com/pattern",
            request = ProjectWriteRequest(name = "Cardigan"),
            imageUrls = emptyList(),
            aiFallback = false,
        )
}
