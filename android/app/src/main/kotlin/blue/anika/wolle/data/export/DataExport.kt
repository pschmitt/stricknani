package blue.anika.wolle.data.export

import androidx.work.WorkInfo
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Response

/** Why a data export did not finish. Stored by name in the worker's output. */
enum class ExportFailure {
    /** `401`: the saved token was rejected (the session-expiry handling shows "sign in again"). */
    Unauthorized,

    /** `429`: this account already has a download running on the server. */
    AlreadyRunning,

    /** Anything else: server error, network failure, the chosen file could not be written. */
    Failed,
}

sealed interface ExportResult {
    data class Success(val bytes: Long) : ExportResult

    data class Failure(val reason: ExportFailure) : ExportResult
}

/** The archive formats of the export: .tar.gz is the default, .zip an option. */
enum class ExportFormat(val mimeType: String, val extension: String, val archiveParam: String?) {
    TarGz("application/gzip", "tar.gz", null),
    Zip("application/zip", "zip", "zip");

    companion object {
        fun fromName(name: String?): ExportFormat = entries.firstOrNull { it.name == name } ?: TarGz
    }

    /** The picker's MIME type: an age file is opaque bytes, whatever archive is inside. */
    fun mimeTypeFor(encrypted: Boolean): String = if (encrypted) ENCRYPTED_MIME_TYPE else mimeType
}

/** The content type of an encrypted download (`.tar.gz.age`, `.zip.age`). */
const val ENCRYPTED_MIME_TYPE = "application/octet-stream"

/** Added to the file name of an encrypted download. */
const val ENCRYPTED_EXTENSION = "age"

/**
 * The suggested name in the file picker: `stricknani-export-YYYY-MM-DD.tar.gz` (or `.zip`), with
 * `.age` appended for an [encrypted] download.
 */
fun exportFileName(
    date: LocalDate,
    format: ExportFormat = ExportFormat.TarGz,
    encrypted: Boolean = false,
): String =
    "stricknani-export-$date.${format.extension}" + if (encrypted) ".$ENCRYPTED_EXTENSION" else ""

@Serializable private data class EncryptedExportRequest(val archive: String, val passphrase: String)

/** The JSON body of `POST /api/v2/account/export`; the passphrase never goes into a URL. */
fun encryptedExportBody(format: ExportFormat, passphrase: String): RequestBody =
    Json.encodeToString(EncryptedExportRequest(format.archiveParam ?: "tar.gz", passphrase))
        .toRequestBody("application/json".toMediaType())

@Serializable private data class TicketEnvelope(val data: TicketData)

@Serializable private data class TicketData(val url: String)

private val ticketJson = Json { ignoreUnknownKeys = true }

/** The download URL in the `{ "data": { "url": ... } }` answer, or null when it is not one. */
fun parseExportTicketUrl(body: String): String? =
    runCatching { ticketJson.decodeFromString<TicketEnvelope>(body).data.url }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }

/** The answer to the first step of an encrypted download. */
sealed interface TicketResult {
    data class Ready(val url: String) : TicketResult

    data class Refused(val reason: ExportFailure) : TicketResult
}

/** Maps the status and body of `POST /api/v2/account/export` to a ticket or a failure. */
fun ticketResultFor(httpStatus: Int, body: String?): TicketResult {
    if (httpStatus != 201) return TicketResult.Refused(exportFailureFor(httpStatus))
    val url = body?.let(::parseExportTicketUrl)
    return if (url == null) TicketResult.Refused(ExportFailure.Failed) else TicketResult.Ready(url)
}

/** Maps the HTTP status of `GET /api/v2/account/export` to a failure. */
fun exportFailureFor(httpStatus: Int): ExportFailure =
    when (httpStatus) {
        401 -> ExportFailure.Unauthorized
        429 -> ExportFailure.AlreadyRunning
        else -> ExportFailure.Failed
    }

/** What the Backups screen shows for the export job. */
sealed interface ExportJobStatus {
    data object Idle : ExportJobStatus

    data class Running(val bytes: Long) : ExportJobStatus

    data object Succeeded : ExportJobStatus

    data class Failed(val reason: ExportFailure) : ExportJobStatus
}

/** Maps a WorkManager state, the last reported byte count and the failure name to a status. */
fun exportJobStatus(state: WorkInfo.State?, bytes: Long, failure: String?): ExportJobStatus =
    when (state) {
        null,
        WorkInfo.State.CANCELLED -> ExportJobStatus.Idle
        WorkInfo.State.ENQUEUED,
        WorkInfo.State.BLOCKED,
        WorkInfo.State.RUNNING -> ExportJobStatus.Running(bytes)
        WorkInfo.State.SUCCEEDED -> ExportJobStatus.Succeeded
        WorkInfo.State.FAILED ->
            ExportJobStatus.Failed(
                ExportFailure.entries.firstOrNull { it.name == failure } ?: ExportFailure.Failed
            )
    }

/**
 * Copies [input] to [output] in chunks without holding the body in memory, reporting the bytes
 * written so far after each chunk. Stops promptly when the calling coroutine is cancelled.
 */
suspend fun copyWithProgress(
    input: InputStream,
    output: OutputStream,
    onProgress: (Long) -> Unit,
    bufferSize: Int = 64 * 1024,
): Long {
    val buffer = ByteArray(bufferSize)
    var total = 0L
    while (true) {
        coroutineContext.ensureActive()
        val read = input.read(buffer)
        if (read < 0) break
        output.write(buffer, 0, read)
        total += read
        onProgress(total)
    }
    output.flush()
    return total
}

/**
 * Writes the body of an export [response] to the stream [openOutput] returns. Never reads the body
 * into memory. A missing output (the picked file vanished) or an I/O error is a
 * [ExportFailure.Failed].
 */
suspend fun streamExport(
    response: Response<ResponseBody>,
    openOutput: () -> OutputStream?,
    onProgress: (Long) -> Unit,
): ExportResult {
    if (!response.isSuccessful) {
        response.errorBody()?.close()
        return ExportResult.Failure(exportFailureFor(response.code()))
    }
    val body = response.body() ?: return ExportResult.Failure(ExportFailure.Failed)
    return try {
        body.use { source ->
            val output = openOutput() ?: return ExportResult.Failure(ExportFailure.Failed)
            output.use { sink ->
                source.byteStream().use { input ->
                    ExportResult.Success(copyWithProgress(input, sink, onProgress))
                }
            }
        }
    } catch (_: IOException) {
        ExportResult.Failure(ExportFailure.Failed)
    }
}
