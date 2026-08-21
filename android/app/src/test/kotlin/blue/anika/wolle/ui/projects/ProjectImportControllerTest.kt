package blue.anika.wolle.ui.projects

import blue.anika.wolle.data.api.dto.ProjectWriteRequest
import blue.anika.wolle.data.repository.ImportedProjectPreview
import blue.anika.wolle.data.repository.ProjectImporter
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
