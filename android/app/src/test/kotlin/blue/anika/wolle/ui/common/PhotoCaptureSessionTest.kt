package blue.anika.wolle.ui.common

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PhotoCaptureSessionTest {
    private lateinit var dir: File
    private var clock = 10_000_000_000L
    private var camera = true

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("captures").toFile().resolve("captures")
    }

    @After
    fun tearDown() {
        dir.parentFile?.deleteRecursively()
    }

    private fun session() =
        PhotoCaptureSession(
            dir,
            cameraAvailable = { camera },
            now = { clock },
            newName = { "a.jpg" },
        )

    @Test
    fun `success hands on the written photo`() {
        val ready = session().begin() as CaptureStart.Ready
        assertTrue(ready.file.isFile)
        ready.file.writeBytes(byteArrayOf(1, 2, 3))
        val outcome = session().finish(ready.file, success = true)
        assertEquals(CaptureOutcome.Photo(ready.file), outcome)
        assertTrue(ready.file.exists())
    }

    @Test
    fun `cancel leaves no file behind`() {
        val ready = session().begin() as CaptureStart.Ready
        assertEquals(CaptureOutcome.Cancelled, session().finish(ready.file, success = false))
        assertFalse(ready.file.exists())
    }

    @Test
    fun `success with an empty file counts as cancelled and is deleted`() {
        val ready = session().begin() as CaptureStart.Ready
        assertEquals(CaptureOutcome.Cancelled, session().finish(ready.file, success = true))
        assertFalse(ready.file.exists())
    }

    @Test
    fun `no camera app means unavailable and nothing is created`() {
        camera = false
        assertEquals(CaptureStart.Unavailable, session().begin())
        assertFalse(dir.exists())
    }

    @Test
    fun `stale leftovers are swept when a capture begins`() {
        dir.mkdirs()
        val old = File(dir, "old.jpg").apply { writeBytes(byteArrayOf(1)) }
        old.setLastModified(clock - 2 * 60 * 60 * 1000L)
        val fresh = File(dir, "fresh.jpg").apply { writeBytes(byteArrayOf(1)) }
        fresh.setLastModified(clock - 60_000L)
        session().begin()
        assertFalse(old.exists())
        assertTrue(fresh.exists())
    }
}
