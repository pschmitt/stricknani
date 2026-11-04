package blue.anika.wolle.data.update

import android.content.Intent
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Why an in-app update stopped; each maps to a user-facing message. */
enum class UpdateFailure {
    NO_APK,
    NOT_TRUSTED,
    DOWNLOAD_FAILED,
    INSUFFICIENT_STORAGE,
    CHECKSUM_UNAVAILABLE,
    CHECKSUM_MISMATCH,
    UNREADABLE_APK,
    WRONG_PACKAGE,
    WRONG_VERSION,
    SIGNATURE_MISMATCH,
    DOWNGRADE,
    PERMISSION_NEEDED,
    INSTALL_FAILED,
    INSTALL_CANCELLED,
}

/** Progress of the in-app update, shared by the Home card, the About row and the notification. */
sealed interface UpdateInstallState {
    data object Idle : UpdateInstallState

    data class Downloading(val bytes: Long, val total: Long) : UpdateInstallState {
        /** 0..1, or null while the size is unknown. */
        val fraction: Float?
            get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else null
    }

    data object Verifying : UpdateInstallState

    data object Installing : UpdateInstallState

    /** The system wants a confirmation; see [UpdateInstallController.takeConfirmationIntent]. */
    data object AwaitingConfirmation : UpdateInstallState

    data class Failed(val failure: UpdateFailure, val detail: String? = null) : UpdateInstallState

    val isBusy: Boolean
        get() =
            this is Downloading ||
                this is Verifying ||
                this is Installing ||
                this is AwaitingConfirmation
}

/** Network side of an update: the one place that touches HTTP. */
interface ApkFetcher {
    /** Streams [url] into [dest], reporting (bytes, total); throws on any failure. */
    suspend fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit)

    suspend fun fetchText(url: String): String

    fun freeBytes(dir: File): Long
}

/** Package-manager side: facts about the installed app and about a downloaded APK. */
interface ApkInspector {
    fun installed(): ApkFacts?

    fun archive(file: File): ApkFacts?

    fun canRequestInstalls(): Boolean

    /** An install would need no system confirmation (see [silentInstallPossible]). */
    fun canInstallSilently(): Boolean
}

/** Hands a verified APK to the system installer (the session is committed, not yet finished). */
interface ApkSessionInstaller {
    /** True when the install bypasses the system's consent (Shizuku): no "unknown apps" needed. */
    suspend fun isPrivileged(): Boolean = false

    suspend fun commit(file: File)
}

/**
 * Runs one update: trusted-source check, download, `SHA256SUMS` check, package and signing-key
 * check, then the install session. Only one runs at a time; [state] is what the UI shows. The
 * install itself finishes asynchronously: the system's answer comes in through [onInstallStatus]
 * (Android kills the app on success, so only failures and confirmations are ever seen).
 */
@Singleton
class UpdateInstallController
@Inject
constructor(
    private val fetcher: ApkFetcher,
    private val inspector: ApkInspector,
    private val installer: ApkSessionInstaller,
    private val workDir: UpdateWorkDir,
) {

    private val _state = MutableStateFlow<UpdateInstallState>(UpdateInstallState.Idle)
    val state: StateFlow<UpdateInstallState> = _state.asStateFlow()

    @Volatile private var confirmationIntent: Intent? = null

    @Volatile private var attachedUis = 0

    /** Whether a screen is showing the update, i.e. can open the system's confirmation itself. */
    val uiAttached: Boolean
        get() = attachedUis > 0

    fun attachUi() {
        attachedUis++
    }

    fun detachUi() {
        attachedUis = maxOf(0, attachedUis - 1)
    }

    /** Resets a finished attempt so the UI shows the plain update offer again. */
    fun reset() {
        if (!_state.value.isBusy) _state.value = UpdateInstallState.Idle
    }

    fun markQueued() {
        _state.value = UpdateInstallState.Downloading(0, 0)
    }

    /** The confirmation intent of the pending install, once. */
    fun takeConfirmationIntent(): Intent? = confirmationIntent.also { confirmationIntent = null }

    suspend fun execute(update: AppUpdate) {
        val failure =
            try {
                run(update)
            } catch (e: CancellationException) {
                _state.value = UpdateInstallState.Idle
                throw e
            } catch (e: Exception) {
                fail(UpdateFailure.INSTALL_FAILED, e.message)
            }
        if (failure != null) _state.value = failure
    }

    private suspend fun run(update: AppUpdate): UpdateInstallState.Failed? {
        val asset = update.asset ?: return fail(UpdateFailure.NO_APK)
        val checksumsUrl = update.checksumsUrl ?: return fail(UpdateFailure.CHECKSUM_UNAVAILABLE)
        if (!isTrustedAssetUrl(asset.url) || !isTrustedAssetUrl(checksumsUrl)) {
            return fail(UpdateFailure.NOT_TRUSTED)
        }
        if (!installer.isPrivileged() && !inspector.canRequestInstalls()) {
            return fail(UpdateFailure.PERMISSION_NEEDED)
        }
        val installed = inspector.installed() ?: return fail(UpdateFailure.UNREADABLE_APK)

        val dir = workDir.dir
        dir.mkdirs()
        // Leftovers of earlier attempts would only eat storage.
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "update.apk")

        _state.value = UpdateInstallState.Downloading(0, asset.sizeBytes)
        if (asset.sizeBytes > 0 && fetcher.freeBytes(dir) < asset.sizeBytes * STORAGE_FACTOR) {
            return fail(UpdateFailure.INSUFFICIENT_STORAGE)
        }
        val expected =
            try {
                parseSha256Sums(fetcher.fetchText(checksumsUrl))[asset.name]
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return fail(UpdateFailure.DOWNLOAD_FAILED, e.message)
            } ?: return fail(UpdateFailure.CHECKSUM_UNAVAILABLE)
        try {
            fetcher.download(asset.url, target) { bytes, total ->
                _state.value =
                    UpdateInstallState.Downloading(
                        bytes,
                        total.takeIf { it > 0 } ?: asset.sizeBytes,
                    )
            }
        } catch (e: CancellationException) {
            target.delete()
            throw e
        } catch (e: InsufficientStorageException) {
            target.delete()
            return fail(UpdateFailure.INSUFFICIENT_STORAGE, e.message)
        } catch (e: Exception) {
            target.delete()
            return fail(UpdateFailure.DOWNLOAD_FAILED, e.message)
        }

        _state.value = UpdateInstallState.Verifying
        if (sha256Hex(target) != expected) {
            target.delete()
            return fail(UpdateFailure.CHECKSUM_MISMATCH)
        }
        val archive = inspector.archive(target)
        if (archive == null) {
            target.delete()
            return fail(UpdateFailure.UNREADABLE_APK)
        }
        checkInstallable(installed, archive, update.stableVersion)?.let {
            target.delete()
            return fail(it)
        }

        _state.value = UpdateInstallState.Installing
        try {
            installer.commit(target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            target.delete()
            return fail(UpdateFailure.INSTALL_FAILED, e.message)
        }
        return null
    }

    private fun fail(failure: UpdateFailure, detail: String? = null) =
        UpdateInstallState.Failed(failure, detail)

    /** The system installer's verdict, from the status receiver. */
    fun onInstallStatus(status: InstallStatus) {
        when (status) {
            is InstallStatus.NeedsConfirmation -> {
                confirmationIntent = status.intent
                _state.value = UpdateInstallState.AwaitingConfirmation
            }
            InstallStatus.Success -> _state.value = UpdateInstallState.Idle
            is InstallStatus.Failure -> {
                workDir.dir.listFiles()?.forEach { it.delete() }
                _state.value =
                    UpdateInstallState.Failed(
                        if (status.cancelled) UpdateFailure.INSTALL_CANCELLED
                        else UpdateFailure.INSTALL_FAILED,
                        status.message,
                    )
            }
        }
    }

    private companion object {
        /** APK plus the copy the installer stages. */
        const val STORAGE_FACTOR = 2
    }
}

/** A [PackageInstaller] status, reduced to what the controller reacts to. */
sealed interface InstallStatus {
    data class NeedsConfirmation(val intent: Intent) : InstallStatus

    data object Success : InstallStatus

    data class Failure(val message: String?, val cancelled: Boolean) : InstallStatus
}

class InsufficientStorageException(message: String?) : Exception(message)

/** Where downloaded APKs live; a class so tests can point it at a temp directory. */
class UpdateWorkDir(val dir: File)
