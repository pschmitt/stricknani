package blue.anika.wolle.data.export

import androidx.work.WorkInfo
import java.io.IOException
import java.io.InputStream
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink

/** What a restore added, from `data` of the `200` response. */
@Serializable
data class RestoreSummary(
    @SerialName("format_version") val formatVersion: Int = 1,
    val projects: Int = 0,
    val yarns: Int = 0,
    @SerialName("categories_created") val categoriesCreated: Int = 0,
    @SerialName("media_files") val mediaFiles: Int = 0,
    @SerialName("media_skipped") val mediaSkipped: Int = 0,
)

@Serializable private data class RestoreEnvelope(val data: RestoreSummary)

@Serializable private data class RestoreErrorEnvelope(val error: RestoreErrorBody? = null)

@Serializable private data class RestoreErrorBody(val code: String? = null)

private val restoreJson = Json { ignoreUnknownKeys = true }

/** The summary in a `{ "data": { ... } }` body, or null when the body is not one. */
fun parseRestoreSummary(body: String): RestoreSummary? =
    runCatching { restoreJson.decodeFromString<RestoreEnvelope>(body).data }.getOrNull()

/** The v2 `error.code` of an error body, or null when there is none. */
fun parseRestoreErrorCode(body: String): String? =
    runCatching { restoreJson.decodeFromString<RestoreErrorEnvelope>(body).error?.code }.getOrNull()

/** Why a restore did not finish. Stored by name in the worker's output. */
enum class RestoreFailure {
    /** `400 invalid_archive`: not a Stricknani download. */
    InvalidArchive,

    /** `422 unsupported_format_version`: the file is newer than the server. */
    UnsupportedVersion,

    /** `413`: over the server's size limit. */
    TooLarge,

    /** `429`: this account already has a restore running. */
    AlreadyRunning,

    /** `401`: the saved token was rejected (the session-expiry handling shows "sign in again"). */
    Unauthorized,

    /** `422 passphrase_required`: the file is encrypted and no passphrase was sent. */
    PassphraseRequired,

    /** `422 wrong_passphrase`: the passphrase does not open the file; ask again. */
    WrongPassphrase,

    /** Anything else: server error, network failure, the file could not be read. */
    Failed,
}

/** Maps the status and v2 `error.code` of `POST /api/v2/account/import` to a failure. */
fun restoreFailureFor(httpStatus: Int, errorCode: String?): RestoreFailure =
    when {
        httpStatus == 401 -> RestoreFailure.Unauthorized
        httpStatus == 413 || errorCode == "payload_too_large" -> RestoreFailure.TooLarge
        httpStatus == 429 || errorCode == "rate_limited" -> RestoreFailure.AlreadyRunning
        errorCode == "passphrase_required" -> RestoreFailure.PassphraseRequired
        errorCode == "wrong_passphrase" -> RestoreFailure.WrongPassphrase
        errorCode == "invalid_archive" -> RestoreFailure.InvalidArchive
        errorCode == "unsupported_format_version" -> RestoreFailure.UnsupportedVersion
        else -> RestoreFailure.Failed
    }

sealed interface RestoreResult {
    data class Success(val summary: RestoreSummary) : RestoreResult

    data class Failure(val reason: RestoreFailure) : RestoreResult
}

/** What the Backups screen shows for the restore job. */
sealed interface RestoreJobStatus {
    data object Idle : RestoreJobStatus

    /** [sent] of [total] bytes uploaded; [total] is 0 when the size is unknown. */
    data class Running(val sent: Long, val total: Long) : RestoreJobStatus

    data class Succeeded(val summary: RestoreSummary) : RestoreJobStatus

    data class Failed(val reason: RestoreFailure) : RestoreJobStatus
}

/** Maps a WorkManager state, the reported byte counts and the output to a status. */
fun restoreJobStatus(
    state: WorkInfo.State?,
    sent: Long,
    total: Long,
    failure: String?,
    summary: RestoreSummary,
): RestoreJobStatus =
    when (state) {
        null,
        WorkInfo.State.CANCELLED -> RestoreJobStatus.Idle
        WorkInfo.State.ENQUEUED,
        WorkInfo.State.BLOCKED,
        WorkInfo.State.RUNNING -> RestoreJobStatus.Running(sent, total)
        WorkInfo.State.SUCCEEDED -> RestoreJobStatus.Succeeded(summary)
        WorkInfo.State.FAILED ->
            RestoreJobStatus.Failed(
                RestoreFailure.entries.firstOrNull { it.name == failure } ?: RestoreFailure.Failed
            )
    }

/**
 * Upload progress in whole percent, or null when the size is unknown or nothing is left to send.
 */
fun uploadPercent(sent: Long, total: Long): Int? =
    if (total <= 0 || sent >= total) null else (sent * 100 / total).toInt().coerceIn(0, 99)

/** The upload is complete and the server is working on the file. */
fun isProcessing(sent: Long, total: Long): Boolean = total > 0 && sent >= total

/**
 * The content type for the first bytes of an archive: gzip magic `1f 8b`, zip `PK`. The server
 * decides by the same bytes, so this is only a courtesy header.
 */
fun restoreContentType(head: ByteArray): String =
    when {
        head.size >= 2 && head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte() -> "application/gzip"
        head.size >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() ->
            "application/zip"
        else -> "application/octet-stream"
    }

/** Reads up to [count] bytes from the start of a stream. */
fun readHead(input: InputStream, count: Int = 4): ByteArray {
    val buffer = ByteArray(count)
    var filled = 0
    while (filled < count) {
        val read = input.read(buffer, filled, count - filled)
        if (read < 0) break
        filled += read
    }
    return buffer.copyOf(filled)
}

/**
 * A request body that copies [openInput] to the wire in chunks. The length is unknown (OkHttp sends
 * it chunked), so the declared size of a file provider can never disagree with the bytes sent.
 * [openInput] is called for every write, so OkHttp can retry a failed connection. [onProgress]
 * receives the bytes written so far, restarting at 0 after a retry.
 */
class StreamingRequestBody(
    private val mediaType: MediaType?,
    private val openInput: () -> InputStream,
    private val onProgress: (Long) -> Unit = {},
    private val bufferSize: Int = 64 * 1024,
) : RequestBody() {
    override fun contentType(): MediaType? = mediaType

    override fun contentLength(): Long = -1

    override fun writeTo(sink: BufferedSink) {
        val buffer = ByteArray(bufferSize)
        var total = 0L
        onProgress(0)
        openInput().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                sink.write(buffer, 0, read)
                total += read
                onProgress(total)
            }
        }
    }
}

fun restoreMediaType(head: ByteArray): MediaType? = restoreContentType(head).toMediaTypeOrNull()

/** `Projects: 3`, the shape of every count line. */
fun countLine(label: String, count: Int): String = "$label: $count"

/** Joins count lines with a middle dot, the style of the summary text. */
fun joinSummary(lines: List<String>): String = lines.joinToString(" · ")

internal fun ioFailure(): RestoreResult = RestoreResult.Failure(RestoreFailure.Failed)

internal class RestoreInputMissing : IOException("The chosen file is not available")
