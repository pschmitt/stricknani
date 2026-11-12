package blue.anika.wolle.data.impersonation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionIsolationTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `an impersonation never shares a database or an upload queue with the own account`() {
        assertNotEquals(
            SessionNamespace.databaseName(impersonating = false),
            SessionNamespace.databaseName(impersonating = true),
        )
        assertNotEquals(
            SessionNamespace.uploadsDirectory(impersonating = false),
            SessionNamespace.uploadsDirectory(impersonating = true),
        )
        // The own names are the ones installed apps already use.
        assertEquals("stricknani.db", SessionNamespace.databaseName(false))
        assertEquals("pending-uploads", SessionNamespace.uploadsDirectory(false))
        assertEquals("image_cache", SessionNamespace.imageCacheDirectory(false))
        assertNotEquals(
            SessionNamespace.imageCacheDirectory(false),
            SessionNamespace.imageCacheDirectory(true),
        )
    }

    @Test
    fun `wiping an impersonation leaves the own account's data alone`() {
        val databases = folder.newFolder("databases")
        val files = folder.newFolder("files")
        val own =
            listOf("stricknani.db", "stricknani.db-wal", "stricknani.db-shm").map {
                File(databases, it).apply { writeText("own queue") }
            }
        val impersonation =
            listOf(
                    SessionNamespace.IMPERSONATION_DATABASE,
                    SessionNamespace.IMPERSONATION_DATABASE + "-wal",
                    SessionNamespace.IMPERSONATION_DATABASE + "-shm",
                    SessionNamespace.IMPERSONATION_DATABASE + "-journal",
                )
                .map { File(databases, it).apply { writeText("someone else's data") } }
        val ownUpload =
            File(files, "pending-uploads/a.jpg").apply {
                parentFile.mkdirs()
                writeText("own")
            }
        val impersonatedUpload =
            File(files, SessionNamespace.IMPERSONATION_UPLOADS + "/b.jpg").apply {
                parentFile.mkdirs()
                writeText("theirs")
            }

        val cache = folder.newFolder("cache")
        val ownImage =
            File(cache, "image_cache/own.bin").apply {
                parentFile.mkdirs()
                writeText("own")
            }
        val impersonatedImage =
            File(cache, SessionNamespace.IMPERSONATION_IMAGE_CACHE + "/theirs.bin").apply {
                parentFile.mkdirs()
                writeText("theirs")
            }

        assertTrue(SessionNamespace.wipeImpersonationData({ File(databases, it) }, files, cache))
        assertTrue(ownImage.exists())
        assertFalse(impersonatedImage.exists())

        assertTrue(own.all { it.exists() && it.readText() == "own queue" })
        assertTrue(ownUpload.exists())
        assertTrue(impersonation.none { it.exists() })
        assertFalse(impersonatedUpload.exists())
        assertFalse(impersonatedUpload.parentFile.exists())
    }

    @Test
    fun `wiping with nothing left is fine`() {
        assertTrue(
            SessionNamespace.wipeImpersonationData(
                { File(folder.newFolder(), it) },
                folder.newFolder(),
                folder.newFolder(),
            )
        )
    }

    @Test
    fun `offline writes are refused while impersonating and allowed otherwise`() {
        var online: Boolean? = false
        val impersonating = OfflineWriteGuard(true) { online }
        val own = OfflineWriteGuard(false) { online }

        assertThrowsOffline { impersonating.checkQueueing() }
        online = null // unknown connectivity does not count as online
        assertThrowsOffline { impersonating.checkQueueing() }
        own.checkQueueing()

        online = true
        impersonating.checkQueueing()
    }

    @Test
    fun `the refusal is an IOException so screens show it like any offline failure`() {
        assertTrue(ImpersonationOfflineException() is java.io.IOException)
    }

    @Test
    fun `a session expires at the server's expiry`() {
        val session = ImpersonationSession(7, "a@example.invalid", "A", null, expiresAtSeconds = 100)
        assertFalse(session.isExpired(99))
        assertTrue(session.isExpired(100))
        assertEquals(40, session.secondsLeft(60))
        assertEquals(0, session.secondsLeft(500))
    }

    @Test
    fun `notifications about an account's data are suppressed while impersonating`() {
        val granted = true
        assertTrue(
            blue.anika.wolle.sync.canPostAccountNotifications(34, granted, true, impersonating = false)
        )
        assertFalse(
            blue.anika.wolle.sync.canPostAccountNotifications(34, granted, true, impersonating = true)
        )
    }

    private fun assertThrowsOffline(block: () -> Unit) {
        try {
            block()
            org.junit.Assert.fail("expected the write to be refused")
        } catch (_: ImpersonationOfflineException) {}
    }
}
