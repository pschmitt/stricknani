package blue.anika.wolle.data.export

import blue.anika.wolle.data.settings.StricknaniCredentials
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the "bring my data along" bookkeeping survives a sign-out and a restart. */
interface LocalMoveStore {
    /** Path of the exported file that still waits for an upload offer, or null. */
    var pendingLocalMove: String?

    /** Path of the file an upload is running for; the process may die before the outcome. */
    var uploadingLocalMove: String?
}

/** What Home shows about the local data that waits for a server (T130.2). */
sealed interface LocalMoveOffer {
    /**
     * Ask "Upload your projects from this phone to [host]?" for the session of [credentials]; an
     * answer for any other session is refused.
     */
    data class Ask(val host: String, val credentials: StricknaniCredentials) : LocalMoveOffer

    /** The upload runs; the restore job shows its own progress and notifications. */
    data class Uploading(val host: String) : LocalMoveOffer
}

/** The host of [serverUrl] for the prompt; the URL itself when it has none. */
fun offerHost(serverUrl: String): String =
    serverUrl.substringAfter("://").substringBefore('/').substringBefore('?').ifBlank { serverUrl }

/**
 * Brings the local-only data (T130) to a server: export it into an app-private file, sign out,
 * offer the upload after the next sign-in, upload through the restore path, delete the file.
 *
 * The pending marker is cleared before an upload starts, so one file is never offered or uploaded
 * twice at once. The file is only ever uploaded into the account of the credentials the offer was
 * shown for ([startUpload] compares them with the current ones).
 *
 * [export] writes the export into the given file and tells whether it worked; [upload] starts the
 * restore of that file and tells whether it started (false while another restore runs).
 */
class LocalDataMove(
    private val store: LocalMoveStore,
    private val dir: File,
    private val export: suspend (File) -> Boolean,
    private val upload: (File) -> Boolean,
) {
    private val _pending = MutableStateFlow<File?>(null)

    /** The exported file that waits for an offer; null when there is none. */
    val pending: StateFlow<File?> = _pending.asStateFlow()

    private val _uploading = MutableStateFlow<File?>(null)

    /** The file whose upload is running. */
    val uploading: StateFlow<File?> = _uploading.asStateFlow()

    private var sawRunning = false

    init {
        // A process that died during an upload does not know how it ended. Keep the file and ask
        // again rather than lose the data; the user decides.
        val interrupted = store.uploadingLocalMove
        if (interrupted != null) {
            store.uploadingLocalMove = null
            store.pendingLocalMove = interrupted
        }
        _pending.value = store.pendingLocalMove?.let(::File)?.takeIf { it.isFile }
        if (_pending.value == null) store.pendingLocalMove = null
    }

    /**
     * Step 1 and 2 of the move: exports into `local.tar.gz` and sets the marker. False (and no
     * marker, no file) when the export failed; the caller then stays in local mode.
     */
    suspend fun prepare(): Boolean {
        discard()
        dir.mkdirs()
        val part = File(dir, "$FILE_NAME.part")
        val target = File(dir, FILE_NAME)
        val ok = runCatching { export(part) }.getOrDefault(false) && part.length() > 0
        if (!ok || !part.renameTo(target)) {
            part.delete()
            return false
        }
        store.pendingLocalMove = target.path
        _pending.value = target
        return true
    }

    /**
     * What Home shows. Nothing in local mode, without a session, with an expired one or after "Not
     * now" in this session ([dismissedFor] is the credentials that answered "Not now").
     */
    fun offerFor(
        credentials: StricknaniCredentials,
        localMode: Boolean,
        sessionExpired: Boolean,
        dismissedFor: StricknaniCredentials?,
        pending: File?,
        uploading: File?,
    ): LocalMoveOffer? {
        if (localMode || sessionExpired || !credentials.isValid) return null
        val host = offerHost(credentials.serverUrl)
        return when {
            uploading != null -> LocalMoveOffer.Uploading(host)
            pending != null && dismissedFor != credentials -> LocalMoveOffer.Ask(host, credentials)
            else -> null
        }
    }

    /**
     * "Upload". Clears the marker first, then starts the restore. Refused (false, marker kept) when
     * the session is no longer the one the offer was shown for, when there is nothing to upload or
     * when another restore is running.
     */
    fun startUpload(shownFor: StricknaniCredentials, current: StricknaniCredentials): Boolean {
        if (shownFor != current || !current.isValid) return false
        val file = _pending.value ?: return false
        if (_uploading.value != null) return false
        store.pendingLocalMove = null
        store.uploadingLocalMove = file.path
        _pending.value = null
        _uploading.value = file
        val started = runCatching { upload(file) }.getOrDefault(false)
        // The restore marks itself Running before upload() returns, so whatever status comes next
        // is this job's; a fast job may even skip the Running state for a slow observer.
        sawRunning = started
        if (!started) restoreMarker(file)
        return started
    }

    /** The restore job reported its state. Only a run seen from the start counts. */
    fun onRestoreStatus(status: RestoreJobStatus) {
        val file = _uploading.value ?: return
        when (status) {
            is RestoreJobStatus.Running -> sawRunning = true
            is RestoreJobStatus.Succeeded -> if (sawRunning) finish(file)
            is RestoreJobStatus.Failed -> if (sawRunning) restoreMarker(file)
            RestoreJobStatus.Idle -> Unit
        }
    }

    /** "Discard": the user gives the data up. Deletes the file. */
    fun discard() {
        val files = listOfNotNull(_pending.value, _uploading.value, File(dir, FILE_NAME))
        store.pendingLocalMove = null
        store.uploadingLocalMove = null
        _pending.value = null
        _uploading.value = null
        files.forEach { runCatching { it.delete() } }
        runCatching { File(dir, "$FILE_NAME.part").delete() }
    }

    /** The upload worked: the file has done its job. */
    private fun finish(file: File) {
        store.uploadingLocalMove = null
        _uploading.value = null
        runCatching { file.delete() }
    }

    /** The upload did not go through: keep the file and offer it again. */
    private fun restoreMarker(file: File) {
        store.uploadingLocalMove = null
        store.pendingLocalMove = file.path
        _uploading.value = null
        _pending.value = file.takeIf { it.isFile }
    }

    companion object {
        const val DIR_NAME = "local-move"
        const val FILE_NAME = "local.tar.gz"
    }
}
