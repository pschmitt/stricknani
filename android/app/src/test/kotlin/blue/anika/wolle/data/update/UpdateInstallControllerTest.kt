package blue.anika.wolle.data.update

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UpdateInstallControllerTest {
    private val goodUrl = "https://github.com/pschmitt/stricknani/releases/download/v1.1.0/a.apk"
    private val sumsUrl =
        "https://github.com/pschmitt/stricknani/releases/download/v1.1.0/SHA256SUMS"
    private val apkBytes = "apk-bytes"
    private val apkSha = sha256Hex(apkBytes.toByteArray())

    private lateinit var dir: File
    private val fetcher = FakeFetcher()
    private val inspector = FakeInspector()
    private val installer = FakeInstaller()
    private lateinit var controller: UpdateInstallController

    private val update =
        AppUpdate(
            version = "1.1.0",
            downloadUrl = goodUrl,
            asset = UpdateAsset("a.apk", goodUrl, apkBytes.length.toLong()),
            checksumsUrl = sumsUrl,
            stableVersion = "1.1.0",
        )

    @Before
    fun setUp() {
        dir = createTempDirectory("update-test").toFile()
        fetcher.apk = apkBytes
        fetcher.sums = "$apkSha  a.apk\n"
        controller = UpdateInstallController(fetcher, inspector, installer, UpdateWorkDir(dir))
    }

    @After fun tearDown() = dir.deleteRecursively().let {}

    private fun failure() = assertIs<UpdateInstallState.Failed>(controller.state.value).failure

    @Test
    fun `happy path downloads, verifies and commits the session`() = runTest {
        val seen = mutableListOf<UpdateInstallState>()
        fetcher.onDownload = { seen += controller.state.value }

        controller.execute(update)

        assertEquals(UpdateInstallState.Installing, controller.state.value)
        assertEquals(apkBytes, installer.committed?.readText())
        assertIs<UpdateInstallState.Downloading>(seen.first())
    }

    @Test
    fun `checksum mismatch discards the file and never installs`() = runTest {
        fetcher.sums = "${"0".repeat(64)}  a.apk\n"

        controller.execute(update)

        assertEquals(UpdateFailure.CHECKSUM_MISMATCH, failure())
        assertEquals(null, installer.committed)
        assertFalse(File(dir, "update.apk").exists())
    }

    @Test
    fun `an apk missing from SHA256SUMS is refused`() = runTest {
        fetcher.sums = "$apkSha  other.apk\n"

        controller.execute(update)

        assertEquals(UpdateFailure.CHECKSUM_UNAVAILABLE, failure())
    }

    @Test
    fun `an update without a checksums asset is refused before any download`() = runTest {
        controller.execute(update.copy(checksumsUrl = null))

        assertEquals(UpdateFailure.CHECKSUM_UNAVAILABLE, failure())
        assertEquals(0, fetcher.downloads)
    }

    @Test
    fun `urls outside the repo releases are refused before any download`() = runTest {
        controller.execute(
            update.copy(asset = update.asset!!.copy(url = "https://evil.test/a.apk"))
        )

        assertEquals(UpdateFailure.NOT_TRUSTED, failure())
        assertEquals(0, fetcher.downloads)
    }

    @Test
    fun `a release without an apk for the device offers nothing to install`() = runTest {
        controller.execute(update.copy(asset = null))

        assertEquals(UpdateFailure.NO_APK, failure())
    }

    @Test
    fun `missing install permission is reported first`() = runTest {
        inspector.canRequest = false

        controller.execute(update)

        assertEquals(UpdateFailure.PERMISSION_NEEDED, failure())
        assertEquals(0, fetcher.downloads)
    }

    @Test
    fun `not enough storage is reported before downloading`() = runTest {
        fetcher.free = 1

        controller.execute(update)

        assertEquals(UpdateFailure.INSUFFICIENT_STORAGE, failure())
        assertEquals(0, fetcher.downloads)
    }

    @Test
    fun `a failed download is reported and leaves no file`() = runTest {
        fetcher.downloadError = java.io.IOException("boom")

        controller.execute(update)

        assertEquals(UpdateFailure.DOWNLOAD_FAILED, failure())
        assertFalse(File(dir, "update.apk").exists())
    }

    @Test
    fun `a full disk during the download is reported as insufficient storage`() = runTest {
        fetcher.downloadError = InsufficientStorageException("ENOSPC")

        controller.execute(update)

        assertEquals(UpdateFailure.INSUFFICIENT_STORAGE, failure())
    }

    @Test
    fun `an apk signed with another key is refused`() = runTest {
        inspector.archiveFacts = inspector.archiveFacts.copy(signerDigests = setOf("other"))

        controller.execute(update)

        assertEquals(UpdateFailure.SIGNATURE_MISMATCH, failure())
        assertEquals(null, installer.committed)
    }

    @Test
    fun `a downgrade is refused`() = runTest {
        inspector.installedFacts = inspector.installedFacts.copy(versionName = "2.0.0")
        inspector.archiveFacts = inspector.archiveFacts.copy(versionName = "1.1.0")

        controller.execute(update)

        assertEquals(UpdateFailure.DOWNGRADE, failure())
    }

    @Test
    fun `a privileged install needs no unknown-apps permission`() = runTest {
        inspector.canRequest = false
        installer.privileged = true

        controller.execute(update)

        assertEquals(UpdateInstallState.Installing, controller.state.value)
        assertEquals(apkBytes, installer.committed?.readText())
    }

    @Test
    fun `without the privileged path the missing permission still stops the update`() = runTest {
        inspector.canRequest = false

        controller.execute(update)

        assertEquals(UpdateFailure.PERMISSION_NEEDED, failure())
        assertEquals(null, installer.committed)
    }

    @Test
    fun `an unreadable apk is refused`() = runTest {
        inspector.archiveReadable = false

        controller.execute(update)

        assertEquals(UpdateFailure.UNREADABLE_APK, failure())
    }

    @Test
    fun `a session that cannot be created is a failure`() = runTest {
        installer.error = IllegalStateException("no session")

        controller.execute(update)

        assertEquals(UpdateFailure.INSTALL_FAILED, failure())
    }

    @Test
    fun `installer failures and cancellations arrive asynchronously`() = runTest {
        controller.execute(update)

        controller.onInstallStatus(InstallStatus.Failure("INSTALL_FAILED_X", cancelled = false))
        assertEquals(UpdateFailure.INSTALL_FAILED, failure())

        controller.onInstallStatus(InstallStatus.Failure(null, cancelled = true))
        assertEquals(UpdateFailure.INSTALL_CANCELLED, failure())
        assertFalse(File(dir, "update.apk").exists())
    }

    @Test
    fun `success returns to idle and reset clears a finished failure only`() = runTest {
        controller.execute(update.copy(checksumsUrl = null))
        controller.reset()
        assertEquals(UpdateInstallState.Idle, controller.state.value)

        controller.execute(update)
        controller.reset()
        assertEquals(UpdateInstallState.Installing, controller.state.value)
        assertTrue(controller.state.value.isBusy)
        controller.onInstallStatus(InstallStatus.Success)
        assertEquals(UpdateInstallState.Idle, controller.state.value)
    }

    @Test
    fun `cancelling the download leaves the state idle`() = runTest {
        fetcher.downloadError = CancellationException("cancelled")

        try {
            controller.execute(update)
        } catch (_: CancellationException) {}

        assertEquals(UpdateInstallState.Idle, controller.state.value)
    }

    private inner class FakeFetcher : ApkFetcher {
        var apk = ""
        var sums = ""
        var free = Long.MAX_VALUE
        var downloads = 0
        var downloadError: Exception? = null
        var onDownload: () -> Unit = {}

        override suspend fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
            downloads++
            onDownload()
            downloadError?.let { throw it }
            dest.writeText(apk)
            onProgress(apk.length.toLong(), apk.length.toLong())
        }

        override suspend fun fetchText(url: String) = sums

        override fun freeBytes(dir: File) = free
    }

    private class FakeInspector : ApkInspector {
        var canRequest = true
        var archiveReadable = true
        var installedFacts = ApkFacts("blue.anika.wolle", "1.0.0", 1, setOf("cert"))
        var archiveFacts = ApkFacts("blue.anika.wolle", "1.1.0", 1, setOf("cert"))

        override fun installed() = installedFacts

        override fun archive(file: File) = archiveFacts.takeIf { archiveReadable }

        override fun canRequestInstalls() = canRequest

        override fun canInstallSilently() = false
    }

    private class FakeInstaller : ApkSessionInstaller {
        var committed: File? = null
        var error: Exception? = null
        var privileged = false

        override suspend fun isPrivileged() = privileged

        override suspend fun commit(file: File) {
            error?.let { throw it }
            committed = file
        }
    }
}

private inline fun <reified T> assertIs(value: Any?): T {
    assertTrue("expected ${T::class.simpleName} but was $value", value is T)
    return value as T
}
