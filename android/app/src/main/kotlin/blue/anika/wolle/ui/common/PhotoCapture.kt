package blue.anika.wolle.ui.common

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import blue.anika.wolle.R
import blue.anika.wolle.data.uploads.captureAuthority
import java.io.File
import java.util.UUID
import kotlinx.coroutines.launch

/** What a "Take photo" round trip to the camera app produced. */
sealed interface CaptureOutcome {
    /** A photo is in [file]; hand it on and let the receiver delete it. */
    data class Photo(val file: File) : CaptureOutcome

    /** The user backed out of the camera app (or it wrote nothing): no file is left behind. */
    data object Cancelled : CaptureOutcome
}

/** Where [PhotoCaptureSession.begin] ends up. */
sealed interface CaptureStart {
    /** No camera app can take a picture, so the action is hidden. */
    data object Unavailable : CaptureStart

    /** The camera app should write the picture to [file]. */
    data class Ready(val file: File) : CaptureStart
}

/**
 * The file handling of "Take photo", free of Android types so it is unit-testable. Captures live in
 * [directory] (the app's cache, see `capture_paths.xml`); an old leftover (a crash, a process death
 * while the camera app was open) is swept the next time a capture begins.
 */
class PhotoCaptureSession(
    private val directory: File,
    private val cameraAvailable: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val newName: () -> String = { "photo-${UUID.randomUUID()}.jpg" },
) {
    fun begin(): CaptureStart {
        if (!cameraAvailable()) return CaptureStart.Unavailable
        directory.mkdirs()
        sweepStale()
        val file = File(directory, newName())
        return if (runCatching { file.createNewFile() }.getOrDefault(false)) {
            CaptureStart.Ready(file)
        } else CaptureStart.Unavailable
    }

    /** [success] is the TakePicture result; a cancelled or empty capture deletes its file. */
    fun finish(file: File, success: Boolean): CaptureOutcome {
        if (success && file.isFile && file.length() > 0) return CaptureOutcome.Photo(file)
        file.delete()
        return CaptureOutcome.Cancelled
    }

    private fun sweepStale() {
        val cutoff = now() - STALE_MILLIS
        directory.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    private companion object {
        const val STALE_MILLIS = 60 * 60 * 1000L
    }
}

/** What the editors hold: whether to offer "Take photo" and how to start it. */
@Stable class PhotoCaptureLauncher(val available: Boolean, val launch: () -> Unit)

private const val CAPTURE_DIRECTORY = "captures"

private fun Context.hasCameraApp(): Boolean =
    Intent(MediaStore.ACTION_IMAGE_CAPTURE).resolveActivity(packageManager) != null

/**
 * "Take photo" through the camera app (ACTION_IMAGE_CAPTURE via TakePicture), written to a
 * FileProvider file in the cache. [onPhoto] gets the content URI exactly like a picked image; call
 * `deleteCapturedPhoto` when it is copied. [onPermissionDenied] fires when the CAMERA permission
 * (which the QR scanner already declares, so the camera intent needs it granted) is refused.
 */
@Composable
fun rememberPhotoCapture(
    onPhoto: (Uri) -> Unit,
    onPermissionDenied: () -> Unit,
): PhotoCaptureLauncher {
    val context = LocalContext.current
    val session =
        remember(context) {
            PhotoCaptureSession(
                directory = File(context.cacheDir, CAPTURE_DIRECTORY),
                cameraAvailable = { context.hasCameraApp() },
            )
        }
    val available = remember(context) { context.hasCameraApp() }
    val currentOnPhoto by rememberUpdatedState(onPhoto)
    val currentOnDenied by rememberUpdatedState(onPermissionDenied)
    // Survives the activity being recreated while the camera app is in front.
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }

    val takePicture =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val file = pendingPath?.let(::File)
            pendingPath = null
            if (file != null) {
                when (val outcome = session.finish(file, success)) {
                    is CaptureOutcome.Photo ->
                        currentOnPhoto(
                            FileProvider.getUriForFile(
                                context,
                                captureAuthority(context),
                                outcome.file,
                            )
                        )
                    CaptureOutcome.Cancelled -> Unit
                }
            }
        }
    val start = {
        when (val begin = session.begin()) {
            CaptureStart.Unavailable -> Unit
            is CaptureStart.Ready -> {
                pendingPath = begin.file.absolutePath
                takePicture.launch(
                    FileProvider.getUriForFile(context, captureAuthority(context), begin.file)
                )
            }
        }
    }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) start() else currentOnDenied()
        }
    return remember(available) {
        PhotoCaptureLauncher(available) {
            val granted =
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
            if (granted) start() else permission.launch(Manifest.permission.CAMERA)
        }
    }
}

/** Tells the user (snackbar) that "Take photo" needs the camera permission. */
@Composable
fun rememberCameraDeniedNotifier(snackbarHostState: SnackbarHostState): () -> Unit {
    val scope = rememberCoroutineScope()
    val message = stringResource(R.string.photo_camera_denied)
    return {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showFeedback(message, FeedbackKind.Info)
        }
    }
}
