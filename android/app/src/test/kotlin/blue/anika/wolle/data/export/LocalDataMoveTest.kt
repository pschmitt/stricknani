package blue.anika.wolle.data.export

import blue.anika.wolle.data.settings.StricknaniCredentials
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalDataMoveTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Store(
        override var pendingLocalMove: String? = null,
        override var uploadingLocalMove: String? = null,
    ) : LocalMoveStore

    private val serverA = StricknaniCredentials("https://wolle.example.org", "token-a")
    private val serverB = StricknaniCredentials("https://wolle.example.org", "token-b")
    private val summary = RestoreSummary(projects = 1, yarns = 1)

    private val store = Store()
    private var exportOk = true
    private var exportContent = "archive"
    private var uploads = mutableListOf<File>()
    private var uploadStarts = true

    private fun move(s: Store = store) =
        LocalDataMove(
            store = s,
            dir = File(tmp.root, "local-move"),
            export = { file ->
                if (exportOk) file.writeText(exportContent)
                exportOk
            },
            upload = { file ->
                uploads += file
                uploadStarts
            },
        )

    private fun offer(
        m: LocalDataMove,
        creds: StricknaniCredentials,
        dismissed: StricknaniCredentials? = null,
    ) =
        m.offerFor(
            credentials = creds,
            localMode = false,
            sessionExpired = false,
            dismissedFor = dismissed,
            pending = m.pending.value,
            uploading = m.uploading.value,
        )

    @Test
    fun `a successful export sets the marker and leaves the file`() = runTest {
        val m = move()
        assertTrue(m.prepare())
        val file = m.pending.value!!
        assertEquals(file.path, store.pendingLocalMove)
        assertTrue(file.isFile)
        assertEquals("archive", file.readText())
        assertFalse(File(file.parentFile, "local.tar.gz.part").exists())
    }

    @Test
    fun `a failed export leaves no marker and no file`() = runTest {
        exportOk = false
        val m = move()
        assertFalse(m.prepare())
        assertNull(store.pendingLocalMove)
        assertNull(m.pending.value)
        assertEquals(emptyList<String>(), File(tmp.root, "local-move").list()?.toList().orEmpty())
    }

    @Test
    fun `an empty export counts as failed`() = runTest {
        exportContent = ""
        assertFalse(move().prepare())
        assertNull(store.pendingLocalMove)
    }

    @Test
    fun `the marker survives a restart`() = runTest {
        move().prepare()
        val restarted = move()
        assertTrue(restarted.pending.value!!.isFile)
    }

    @Test
    fun `a marker without a file is dropped`() {
        val s = Store(pendingLocalMove = File(tmp.root, "gone").path)
        val m = move(s)
        assertNull(m.pending.value)
        assertNull(s.pendingLocalMove)
    }

    @Test
    fun `the offer asks for the host of the signed in server`() = runTest {
        val m = move()
        m.prepare()
        assertEquals(LocalMoveOffer.Ask("wolle.example.org", serverA), offer(m, serverA))
    }

    @Test
    fun `no offer in local mode, with an expired session or without a file`() = runTest {
        val m = move()
        assertNull(offer(m, serverA))
        m.prepare()
        assertNull(
            m.offerFor(
                serverA,
                localMode = true,
                sessionExpired = false,
                dismissedFor = null,
                pending = m.pending.value,
                uploading = null,
            )
        )
        assertNull(
            m.offerFor(
                serverA,
                localMode = false,
                sessionExpired = true,
                dismissedFor = null,
                pending = m.pending.value,
                uploading = null,
            )
        )
    }

    @Test
    fun `not now keeps the file and the marker and hides the offer for that session only`() =
        runTest {
            val m = move()
            m.prepare()
            assertNull(offer(m, serverA, dismissed = serverA))
            // A later session (new token) is offered the data again.
            assertEquals(
                LocalMoveOffer.Ask("wolle.example.org", serverB),
                offer(m, serverB, dismissed = serverA),
            )
            assertTrue(m.pending.value!!.isFile)
            assertEquals(m.pending.value!!.path, store.pendingLocalMove)
        }

    @Test
    fun `upload clears the marker before the restore starts`() = runTest {
        val m = move()
        m.prepare()
        val file = m.pending.value!!
        var markerDuringUpload: String? = "unset"
        val checking =
            LocalDataMove(
                store,
                File(tmp.root, "local-move"),
                export = { true },
                upload = {
                    markerDuringUpload = store.pendingLocalMove
                    true
                },
            )
        assertTrue(checking.startUpload(serverA, serverA))
        assertNull(markerDuringUpload)
        assertNull(store.pendingLocalMove)
        assertNull(checking.pending.value)
        assertEquals(file, checking.uploading.value)
        assertTrue(offer(checking, serverA) is LocalMoveOffer.Uploading)
    }

    @Test
    fun `the same file is offered and uploaded only once`() = runTest {
        val m = move()
        m.prepare()
        assertTrue(m.startUpload(serverA, serverA))
        assertFalse(m.startUpload(serverA, serverA))
        assertEquals(1, uploads.size)
        assertFalse(offer(m, serverA) is LocalMoveOffer.Ask)
        // A restart in the middle of the upload does not make a second marker out of nothing.
        m.onRestoreStatus(RestoreJobStatus.Running(0, 10))
        m.onRestoreStatus(RestoreJobStatus.Succeeded(summary))
        assertNull(move().pending.value)
        assertFalse(m.startUpload(serverA, serverA))
        assertEquals(1, uploads.size)
    }

    @Test
    fun `a different account never gets the upload`() = runTest {
        val m = move()
        m.prepare()
        assertFalse(m.startUpload(serverA, serverB))
        assertTrue(uploads.isEmpty())
        // Nothing was consumed: the marker and the file are still there.
        assertEquals(m.pending.value!!.path, store.pendingLocalMove)
        assertTrue(m.pending.value!!.isFile)
    }

    @Test
    fun `success deletes the file`() = runTest {
        val m = move()
        m.prepare()
        val file = m.pending.value!!
        m.startUpload(serverA, serverA)
        m.onRestoreStatus(RestoreJobStatus.Running(0, 10))
        assertTrue(file.isFile)
        m.onRestoreStatus(RestoreJobStatus.Succeeded(summary))
        assertFalse(file.exists())
        assertNull(m.uploading.value)
        assertNull(m.pending.value)
        assertNull(store.pendingLocalMove)
        assertNull(store.uploadingLocalMove)
    }

    @Test
    fun `an old finished status does not delete the file`() = runTest {
        val m = move()
        m.prepare()
        val file = m.pending.value!!
        // The restore did not start (another one runs): the marker comes back, nothing is lost.
        uploadStarts = false
        assertFalse(m.startUpload(serverA, serverA))
        assertTrue(file.isFile)
        assertEquals(file.path, store.pendingLocalMove)
        m.onRestoreStatus(RestoreJobStatus.Succeeded(summary))
        assertTrue(file.isFile)
    }

    @Test
    fun `a failed upload keeps the file and offers it again`() = runTest {
        val m = move()
        m.prepare()
        val file = m.pending.value!!
        m.startUpload(serverA, serverA)
        m.onRestoreStatus(RestoreJobStatus.Running(0, 10))
        m.onRestoreStatus(RestoreJobStatus.Failed(RestoreFailure.Failed))
        assertTrue(file.isFile)
        assertEquals(file.path, store.pendingLocalMove)
        assertNull(store.uploadingLocalMove)
        assertTrue(offer(m, serverA) is LocalMoveOffer.Ask)
    }

    @Test
    fun `a process that died during the upload offers the file again`() = runTest {
        val m = move()
        m.prepare()
        val file = m.pending.value!!
        m.startUpload(serverA, serverA)
        val restarted = move()
        assertEquals(file, restarted.pending.value)
        assertEquals(file.path, store.pendingLocalMove)
        assertNull(store.uploadingLocalMove)
    }

    @Test
    fun `discard deletes the file and clears the marker`() = runTest {
        val m = move()
        m.prepare()
        val file = m.pending.value!!
        m.discard()
        assertFalse(file.exists())
        assertNull(m.pending.value)
        assertNull(store.pendingLocalMove)
        assertNull(offer(m, serverA))
    }

    @Test
    fun `a new export replaces the old file`() = runTest {
        val m = move()
        m.prepare()
        exportContent = "second"
        m.prepare()
        assertEquals("second", m.pending.value!!.readText())
    }

    @Test
    fun `the host is the part between scheme and path`() {
        assertEquals("wolle.example.org", offerHost("https://wolle.example.org"))
        assertEquals("host:8080", offerHost("http://host:8080/app"))
        assertEquals("bare", offerHost("bare"))
    }
}
