package blue.anika.wolle.data.export

import androidx.work.WorkInfo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class DataExportTest {
    private val gzip = "application/gzip".toMediaType()

    @Test
    fun `file name carries the date`() {
        assertEquals(
            "stricknani-export-2026-11-07.tar.gz",
            exportFileName(LocalDate.of(2026, 11, 7)),
        )
        assertEquals(
            "stricknani-export-2026-01-02.tar.gz",
            exportFileName(LocalDate.of(2026, 1, 2)),
        )
        assertEquals(
            "stricknani-export-2026-11-07.zip",
            exportFileName(LocalDate.of(2026, 11, 7), ExportFormat.Zip),
        )
    }

    @Test
    fun `format drives mime type and query`() {
        assertEquals("application/gzip", ExportFormat.TarGz.mimeType)
        assertEquals(null, ExportFormat.TarGz.archiveParam)
        assertEquals("application/zip", ExportFormat.Zip.mimeType)
        assertEquals("zip", ExportFormat.Zip.archiveParam)
        assertEquals(ExportFormat.Zip, ExportFormat.fromName("Zip"))
        assertEquals(ExportFormat.TarGz, ExportFormat.fromName(null))
        assertEquals(ExportFormat.TarGz, ExportFormat.fromName("bogus"))
    }

    @Test
    fun `http status maps to a failure`() {
        assertEquals(ExportFailure.Unauthorized, exportFailureFor(401))
        assertEquals(ExportFailure.AlreadyRunning, exportFailureFor(429))
        assertEquals(ExportFailure.Failed, exportFailureFor(500))
        assertEquals(ExportFailure.Failed, exportFailureFor(404))
    }

    @Test
    fun `job status follows the work state`() {
        assertEquals(ExportJobStatus.Idle, exportJobStatus(null, 0, null))
        assertEquals(ExportJobStatus.Idle, exportJobStatus(WorkInfo.State.CANCELLED, 0, null))
        assertEquals(
            ExportJobStatus.Running(0),
            exportJobStatus(WorkInfo.State.ENQUEUED, 0, null),
        )
        assertEquals(
            ExportJobStatus.Running(42),
            exportJobStatus(WorkInfo.State.RUNNING, 42, null),
        )
        assertEquals(ExportJobStatus.Succeeded, exportJobStatus(WorkInfo.State.SUCCEEDED, 9, null))
        assertEquals(
            ExportJobStatus.Failed(ExportFailure.AlreadyRunning),
            exportJobStatus(WorkInfo.State.FAILED, 0, "AlreadyRunning"),
        )
        assertEquals(
            ExportJobStatus.Failed(ExportFailure.Failed),
            exportJobStatus(WorkInfo.State.FAILED, 0, "nonsense"),
        )
    }

    @Test
    fun `success streams every byte and reports progress`() = runBlocking {
        val payload = ByteArray(200_000) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        val progress = mutableListOf<Long>()

        val result =
            streamExport(
                Response.success(payload.toResponseBody(gzip)),
                openOutput = { out },
                onProgress = { progress += it },
            )

        assertEquals(ExportResult.Success(payload.size.toLong()), result)
        assertArrayEquals(payload, out.toByteArray())
        assertTrue(progress.size > 1)
        assertEquals(payload.size.toLong(), progress.last())
        assertEquals(progress.sorted(), progress)
    }

    @Test
    fun `body is read in chunks not all at once`() = runBlocking {
        val reads = mutableListOf<Int>()
        val source =
            object : InputStream() {
                private var left = 300_000

                override fun read(): Int = error("single-byte read")

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    reads += len
                    if (left == 0) return -1
                    val n = minOf(len, left)
                    left -= n
                    return n
                }
            }
        val out = ByteArrayOutputStream()

        val total = copyWithProgress(source, out, {}, bufferSize = 8 * 1024)

        assertEquals(300_000L, total)
        assertTrue(reads.all { it <= 8 * 1024 })
    }

    @Test
    fun `error statuses map without opening the output`() = runBlocking {
        for ((code, reason) in
            listOf(
                401 to ExportFailure.Unauthorized,
                429 to ExportFailure.AlreadyRunning,
                500 to ExportFailure.Failed,
            )) {
            var opened = false
            val result =
                streamExport(
                    Response.error(
                        code,
                        """{"error":{"code":"x"}}"""
                            .toResponseBody("application/json".toMediaType()),
                    ),
                    openOutput = {
                        opened = true
                        ByteArrayOutputStream()
                    },
                    onProgress = {},
                )
            assertEquals(ExportResult.Failure(reason), result)
            assertTrue(!opened)
        }
    }

    @Test
    fun `missing output is a failure`() = runBlocking {
        val result =
            streamExport(
                Response.success("x".toResponseBody(gzip)),
                openOutput = { null },
                onProgress = {},
            )
        assertEquals(ExportResult.Failure(ExportFailure.Failed), result)
    }

    @Test
    fun `io error while writing is a failure`() = runBlocking {
        val broken =
            object : OutputStream() {
                override fun write(b: Int) = throw IOException("disk full")

                override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("full")
            }
        val result =
            streamExport(
                Response.success(ByteArrayInputStream(ByteArray(10)).readBytes().toBody()),
                openOutput = { broken },
                onProgress = {},
            )
        assertEquals(ExportResult.Failure(ExportFailure.Failed), result)
    }

    private fun ByteArray.toBody(): ResponseBody = toResponseBody(gzip)
}
