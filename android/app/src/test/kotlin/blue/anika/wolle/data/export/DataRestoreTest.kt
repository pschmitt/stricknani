package blue.anika.wolle.data.export

import androidx.work.WorkInfo
import java.io.ByteArrayInputStream
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DataRestoreTest {
    @Test
    fun `content type is sniffed from the magic bytes`() {
        assertEquals("application/gzip", restoreContentType(byteArrayOf(0x1f, 0x8b.toByte(), 8)))
        assertEquals("application/zip", restoreContentType("PK\u0003\u0004".toByteArray()))
        assertEquals("application/octet-stream", restoreContentType("hello".toByteArray()))
        assertEquals("application/octet-stream", restoreContentType(byteArrayOf(0x1f)))
        assertEquals("application/octet-stream", restoreContentType(byteArrayOf()))
        assertEquals(
            "application/gzip",
            restoreMediaType(byteArrayOf(0x1f, 0x8b.toByte()))?.toString(),
        )
    }

    @Test
    fun `head reading stops at the end of a short stream`() {
        assertArrayEquals(byteArrayOf(1, 2), readHead(ByteArrayInputStream(byteArrayOf(1, 2))))
        assertArrayEquals(
            byteArrayOf(1, 2, 3, 4),
            readHead(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5, 6))),
        )
    }

    @Test
    fun `summary is parsed from the data envelope`() {
        val body =
            """{"data":{"format_version":1,"projects":12,"yarns":30,"categories_created":2,
                "media_files":84,"media_skipped":1,"extra":true}}"""
        val summary = parseRestoreSummary(body)
        assertEquals(RestoreSummary(1, 12, 30, 2, 84, 1), summary)
        assertNull(parseRestoreSummary("not json"))
        assertNull(parseRestoreSummary("""{"error":{"code":"x"}}"""))
    }

    @Test
    fun `error code is parsed from the error envelope`() {
        assertEquals(
            "invalid_archive",
            parseRestoreErrorCode("""{"error":{"code":"invalid_archive","message":"m"}}"""),
        )
        assertNull(parseRestoreErrorCode(""))
        assertNull(parseRestoreErrorCode("""{"data":{}}"""))
    }

    @Test
    fun `status and code map to a failure`() {
        assertEquals(RestoreFailure.InvalidArchive, restoreFailureFor(400, "invalid_archive"))
        assertEquals(
            RestoreFailure.UnsupportedVersion,
            restoreFailureFor(422, "unsupported_format_version"),
        )
        assertEquals(RestoreFailure.TooLarge, restoreFailureFor(413, "payload_too_large"))
        assertEquals(RestoreFailure.TooLarge, restoreFailureFor(413, null))
        assertEquals(RestoreFailure.AlreadyRunning, restoreFailureFor(429, "rate_limited"))
        assertEquals(RestoreFailure.AlreadyRunning, restoreFailureFor(429, null))
        assertEquals(RestoreFailure.Unauthorized, restoreFailureFor(401, null))
        assertEquals(RestoreFailure.Failed, restoreFailureFor(500, null))
        assertEquals(RestoreFailure.Failed, restoreFailureFor(400, "validation_error"))
    }

    @Test
    fun `job status follows the work state`() {
        val summary = RestoreSummary(projects = 3)
        assertEquals(RestoreJobStatus.Idle, restoreJobStatus(null, 0, 0, null, summary))
        assertEquals(
            RestoreJobStatus.Running(5, 10),
            restoreJobStatus(WorkInfo.State.RUNNING, 5, 10, null, summary),
        )
        assertEquals(
            RestoreJobStatus.Succeeded(summary),
            restoreJobStatus(WorkInfo.State.SUCCEEDED, 0, 0, null, summary),
        )
        assertEquals(
            RestoreJobStatus.Failed(RestoreFailure.TooLarge),
            restoreJobStatus(WorkInfo.State.FAILED, 0, 0, "TooLarge", summary),
        )
        assertEquals(
            RestoreJobStatus.Failed(RestoreFailure.Failed),
            restoreJobStatus(WorkInfo.State.FAILED, 0, 0, "bogus", summary),
        )
    }

    @Test
    fun `percent is known only while uploading a sized file`() {
        assertEquals(0, uploadPercent(0, 200))
        assertEquals(50, uploadPercent(100, 200))
        assertNull(uploadPercent(200, 200))
        assertNull(uploadPercent(5, 0))
        assertTrue(isProcessing(200, 200))
        assertTrue(!isProcessing(10, 200))
        assertTrue(!isProcessing(10, 0))
    }

    @Test
    fun `summary lines are joined with a middle dot`() {
        assertEquals(
            "Projects: 1 · Yarns: 2",
            joinSummary(listOf(countLine("Projects", 1), countLine("Yarns", 2))),
        )
    }

    @Test
    fun `request body writes exactly the input and reports progress`() {
        val data = ByteArray(10_000) { (it % 251).toByte() }
        val progress = mutableListOf<Long>()
        val body =
            StreamingRequestBody(
                mediaType = restoreMediaType(data),
                openInput = { ByteArrayInputStream(data) },
                onProgress = { progress += it },
                bufferSize = 4096,
            )
        assertEquals(-1L, body.contentLength())
        val sink = Buffer()
        body.writeTo(sink)
        assertArrayEquals(data, sink.readByteArray())
        assertEquals(listOf(0L, 4096L, 8192L, 10_000L), progress)
    }

    @Test
    fun `request body reopens the input for a retry`() {
        var opened = 0
        val body =
            StreamingRequestBody(
                null,
                openInput = {
                    opened++
                    ByteArrayInputStream(byteArrayOf(7))
                },
            )
        repeat(2) {
            val sink = Buffer()
            body.writeTo(sink)
            assertArrayEquals(byteArrayOf(7), sink.readByteArray())
        }
        assertEquals(2, opened)
    }
}
