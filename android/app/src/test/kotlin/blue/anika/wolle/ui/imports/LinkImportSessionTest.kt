package blue.anika.wolle.ui.imports

import blue.anika.wolle.data.api.v2.ImportDraftDto
import blue.anika.wolle.data.api.v2.ImportJobDto
import blue.anika.wolle.data.api.v2.ImportJobErrorBody
import blue.anika.wolle.data.api.v2.ImportJobErrorDto
import blue.anika.wolle.data.api.v2.ImportPreviewDto
import blue.anika.wolle.data.api.v2.MAX_IMPORT_SOURCE_BYTES
import blue.anika.wolle.data.imports.ImportDraftSource
import blue.anika.wolle.data.imports.ImportFile
import blue.anika.wolle.data.imports.ImportTarget
import blue.anika.wolle.data.imports.fileImportJob
import blue.anika.wolle.ui.common.ImportFailure
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LinkImportSessionTest {
    private class FakeDrafts(val answers: MutableList<ImportJobDto>) : ImportDraftSource {
        var started = 0
        val texts = mutableListOf<String>()
        val files = mutableListOf<ImportFile>()
        var fileFailure: Exception? = null
        val deleted = mutableListOf<String>()

        override suspend fun start(url: String, target: ImportTarget): ImportJobDto {
            started++
            return ImportJobDto(id = "d1", sourceUrl = url)
        }

        override suspend fun startText(text: String): ImportJobDto {
            texts += text
            return ImportJobDto(id = "t1")
        }

        override suspend fun previewFile(file: ImportFile): ImportJobDto {
            fileFailure?.let { throw it }
            files += file
            return fileImportJob(ImportPreviewDto(draft = ImportDraftDto()), useAi = false)
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

    @Test
    fun `pasted text becomes a draft that is followed like a link`() = runTest {
        val ready = ImportJobDto(id = "t1", sourceKind = "text", status = "ready")
        val fake = FakeDrafts(mutableListOf(ready))
        val remembered = mutableListOf<String?>()
        val session =
            LinkImportSession(backgroundScope, fake, ImportTarget.Project) { remembered += it }

        session.startText("Cast on 48 stitches")
        runCurrent()

        assertEquals(listOf("Cast on 48 stitches"), fake.texts)
        assertEquals(listOf<String?>("t1"), remembered)
        assertEquals(EditorImportState.Ready(ready), session.state.value)
    }

    @Test
    fun `blank text starts nothing`() = runTest {
        val fake = FakeDrafts(mutableListOf(ImportJobDto(id = "d1")))
        val session = LinkImportSession(backgroundScope, fake, ImportTarget.Project)

        session.startText("   ")
        runCurrent()

        assertTrue(fake.texts.isEmpty())
        assertEquals(EditorImportState.Idle, session.state.value)
    }

    @Test
    fun `text over the limit is refused before it is sent`() = runTest {
        val fake = FakeDrafts(mutableListOf(ImportJobDto(id = "d1")))
        val session = LinkImportSession(backgroundScope, fake, ImportTarget.Project)

        session.startText("a".repeat(MAX_IMPORT_SOURCE_BYTES + 1))
        runCurrent()

        assertTrue(fake.texts.isEmpty())
        assertEquals(EditorImportState.Failed("", ImportFailure.TooLarge), session.state.value)
    }

    @Test
    fun `a file is read at once and leaves no draft behind`() = runTest {
        val fake = FakeDrafts(mutableListOf(ImportJobDto(id = "d1")))
        val remembered = mutableListOf<String?>()
        val session =
            LinkImportSession(backgroundScope, fake, ImportTarget.Project) { remembered += it }
        val file = ImportFile("pattern.pdf", "application/pdf", byteArrayOf(1, 2, 3))

        session.startFile(file)
        runCurrent()

        assertEquals(listOf(file), fake.files)
        val ready = session.state.value as EditorImportState.Ready
        assertEquals("", ready.draft.id)
        assertTrue(ready.draft.isReady)
        assertTrue(remembered.isEmpty())
        session.finish()
        assertTrue(fake.deleted.isEmpty())
    }

    @Test
    fun `a file over the limit is refused before it is sent`() = runTest {
        val fake = FakeDrafts(mutableListOf(ImportJobDto(id = "d1")))
        val session = LinkImportSession(backgroundScope, fake, ImportTarget.Project)

        session.startFile(
            ImportFile("big.pdf", "application/pdf", ByteArray(MAX_IMPORT_SOURCE_BYTES + 1))
        )
        runCurrent()

        assertTrue(fake.files.isEmpty())
        assertEquals(EditorImportState.Failed("", ImportFailure.TooLarge), session.state.value)
    }

    @Test
    fun `a file the server cannot reach fails as offline`() = runTest {
        val fake = FakeDrafts(mutableListOf(ImportJobDto(id = "d1")))
        fake.fileFailure = java.io.IOException("down")
        val session = LinkImportSession(backgroundScope, fake, ImportTarget.Project)

        session.startFile(ImportFile("a.png", "image/png", byteArrayOf(1)))
        runCurrent()

        assertEquals(EditorImportState.Failed("", ImportFailure.Offline), session.state.value)
    }

    @Test
    fun `a source that cannot be opened reports why`() = runTest {
        val session =
            LinkImportSession(
                backgroundScope,
                FakeDrafts(mutableListOf(ImportJobDto(id = "d1"))),
                ImportTarget.Project,
            )

        session.fail(ImportFailure.Unreadable)

        assertEquals(EditorImportState.Failed("", ImportFailure.Unreadable), session.state.value)
    }

    @Test
    fun `the button needs something in the chosen source`() {
        assertTrue(canStartImport(ImportSource.Link, "https://x.y", "", false))
        assertFalse(canStartImport(ImportSource.Link, " ", "text", true))
        assertTrue(canStartImport(ImportSource.Text, "", "text", false))
        assertFalse(canStartImport(ImportSource.Text, "https://x.y", "  ", true))
        assertTrue(canStartImport(ImportSource.File, "", "", true))
        assertFalse(canStartImport(ImportSource.File, "https://x.y", "text", false))
    }
}
