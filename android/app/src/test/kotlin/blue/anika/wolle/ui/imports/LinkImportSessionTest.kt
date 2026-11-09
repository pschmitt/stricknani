package blue.anika.wolle.ui.imports

import blue.anika.wolle.data.api.v2.ImportJobDto
import blue.anika.wolle.data.api.v2.ImportJobErrorBody
import blue.anika.wolle.data.api.v2.ImportJobErrorDto
import blue.anika.wolle.data.imports.ImportDraftSource
import blue.anika.wolle.data.imports.ImportTarget
import blue.anika.wolle.ui.common.ImportFailure
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LinkImportSessionTest {
    private class FakeDrafts(val answers: MutableList<ImportJobDto>) : ImportDraftSource {
        var started = 0
        val deleted = mutableListOf<String>()

        override suspend fun start(url: String, target: ImportTarget): ImportJobDto {
            started++
            return ImportJobDto(id = "d1", sourceUrl = url)
        }

        override suspend fun get(id: String): ImportJobDto =
            if (answers.size > 1) answers.removeAt(0) else answers.first()

        override suspend fun delete(id: String) {
            deleted += id
        }

        override fun seen(draft: ImportJobDto) = Unit
    }

    @Test
    fun `polls until the draft is ready, then hands it over`() = runTest {
        val ready = ImportJobDto(id = "d1", status = "ready")
        val fake = FakeDrafts(mutableListOf(ImportJobDto(id = "d1"), ready))
        val remembered = mutableListOf<String?>()
        val session =
            LinkImportSession(backgroundScope, fake, ImportTarget.Project) { remembered += it }

        session.start("https://example.com/p")
        runCurrent()
        assertTrue(session.state.value is EditorImportState.Running)
        advanceTimeBy(2_100)
        runCurrent()
        assertEquals(EditorImportState.Ready(ready), session.state.value)
        assertEquals(listOf<String?>("d1"), remembered)

        session.applied()
        session.finish()
        assertEquals(listOf("d1"), fake.deleted)
    }

    @Test
    fun `a failed draft reports why`() = runTest {
        val failed =
            ImportJobDto(
                id = "d1",
                status = "failed",
                errorStatus = 502,
                error = ImportJobErrorDto(ImportJobErrorBody(code = "import_blocked")),
            )
        val session =
            LinkImportSession(backgroundScope, FakeDrafts(mutableListOf(failed)), ImportTarget.Yarn)

        session.start("https://example.com/y")
        runCurrent()

        assertEquals(
            EditorImportState.Failed("https://example.com/y", ImportFailure.Blocked),
            session.state.value,
        )
    }

    @Test
    fun `something that is not a link does not start an import`() = runTest {
        val fake = FakeDrafts(mutableListOf(ImportJobDto(id = "d1")))
        val session = LinkImportSession(backgroundScope, fake, ImportTarget.Project)

        session.start("not a link")

        assertEquals(0, fake.started)
        assertEquals(
            EditorImportState.Failed("not a link", ImportFailure.InvalidUrl),
            session.state.value,
        )
    }
}
