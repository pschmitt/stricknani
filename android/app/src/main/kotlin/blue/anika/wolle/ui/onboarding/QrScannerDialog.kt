@file:Suppress("UnsafeOptInUsageError")

package blue.anika.wolle.ui.onboarding

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import blue.anika.wolle.scanner.BarcodeAnalyzer
import java.util.concurrent.Executors
import timber.log.Timber

/**
 * Full-screen QR scanner for onboarding (SNA-13) - a plain [Dialog] like `ImageViewerDialog`, not
 * a nav route, since it's a one-shot capture rather than a real destination. Deliberately much
 * simpler than nyetbox's `ScannerScreen` (no lens switching/zoom/torch/tap-to-focus): a single
 * back-camera preview is all a one-time setup-code scan needs.
 */
@Composable
fun QrScannerDialog(onResult: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasCameraPermission = granted
        }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize()) {
            if (hasCameraPermission) {
                ScannerCameraPreview(onCodeScanned = onResult)
                ScannerViewfinder(modifier = Modifier.fillMaxSize())
            } else {
                Text(
                    "Camera permission is required to scan the setup code",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }
}

@Composable
private fun ScannerCameraPreview(onCodeScanned: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val cameraProviderFuture = remember(context) { ProcessCameraProvider.getInstance(context) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    DisposableEffect(Unit) { onDispose { cameraExecutor.shutdown() } }

    DisposableEffect(previewView) {
        val view = previewView
        if (view == null) {
            onDispose {}
        } else {
            val provider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
            val analysis =
                ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(cameraExecutor, BarcodeAnalyzer(onCodeScanned)) }
            runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                }
                .onFailure { Timber.e(it, "Unable to bind camera for QR scanning") }
            onDispose { runCatching { provider.unbindAll() } }
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PreviewView(ctx).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
        },
        update = { view -> previewView = view },
    )
}

/**
 * A dimmed frame around a centered square cutout, purely cosmetic - the analyzer scans the whole
 * camera frame regardless of what's inside the square.
 */
@Composable
private fun ScannerViewfinder(modifier: Modifier = Modifier) {
    val dim = Color.Black.copy(alpha = 0.55f)
    Canvas(modifier = modifier) {
        val squareSize = size.minDimension * 0.65f
        val left = (size.width - squareSize) / 2f
        val top = (size.height - squareSize) / 2f
        val right = left + squareSize
        val bottom = top + squareSize

        drawRect(color = dim, topLeft = Offset(0f, 0f), size = Size(size.width, top))
        drawRect(
            color = dim,
            topLeft = Offset(0f, bottom),
            size = Size(size.width, size.height - bottom),
        )
        drawRect(color = dim, topLeft = Offset(0f, top), size = Size(left, squareSize))
        drawRect(
            color = dim,
            topLeft = Offset(right, top),
            size = Size(size.width - right, squareSize),
        )

        drawRoundRect(
            color = Color.White,
            topLeft = Offset(left, top),
            size = Size(squareSize, squareSize),
            cornerRadius = CornerRadius(24f, 24f),
            style = Stroke(width = 3.dp.toPx()),
        )
    }
}
