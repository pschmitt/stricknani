package blue.anika.wolle.ui.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.R
import blue.anika.wolle.ui.common.CropBox
import blue.anika.wolle.ui.common.CropStage
import blue.anika.wolle.ui.common.PhotoEditMath
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Local avatar editing: crop and rotate the selected image before it reaches the upload API. */
@Composable
internal fun AvatarCropDialog(
    source: Uri,
    onDismiss: () -> Unit,
    onReady: (Uri) -> Unit,
) {
    val context = LocalContext.current
    val painter = rememberAsyncImagePainter(model = source)
    val painterState by painter.state.collectAsStateWithLifecycle()
    val natural =
        (painterState as? AsyncImagePainter.State.Success)?.result?.image?.let {
            if (it.width > 0 && it.height > 0) it.width to it.height else null
        }
    var rotation by remember(source) { mutableIntStateOf(0) }
    var crop by remember(source) { mutableStateOf(CropBox.Whole) }
    var processing by remember(source) { mutableStateOf(false) }
    var failed by remember(source) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Dialog(
        onDismissRequest = { if (!processing) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(
                Modifier.fillMaxSize().imePadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    stringResource(R.string.photo_edit_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    stringResource(R.string.photo_edit_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(
                    Modifier.weight(1f)
                        .fillMaxWidth()
                        .background(Color(0xFF14141A), MaterialTheme.shapes.large),
                    contentAlignment = Alignment.Center,
                ) {
                    if (natural == null) CircularProgressIndicator()
                    else CropStage(painter, natural, rotation, crop, !processing) { crop = it }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = natural != null && !processing,
                        onClick = {
                            rotation = PhotoEditMath.rotateBy(rotation, -1)
                            crop = PhotoEditMath.rotateCrop(crop, -1)
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.RotateLeft,
                            contentDescription = stringResource(R.string.photo_edit_rotate_left),
                        )
                    }
                    OutlinedButton(
                        enabled = natural != null && !processing,
                        onClick = {
                            rotation = PhotoEditMath.rotateBy(rotation, 1)
                            crop = PhotoEditMath.rotateCrop(crop, 1)
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.RotateRight,
                            contentDescription = stringResource(R.string.photo_edit_rotate_right),
                        )
                    }
                    OutlinedButton(
                        enabled = !processing,
                        onClick = {
                            rotation = 0
                            crop = CropBox.Whole
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            Icons.Filled.Restore,
                            contentDescription = stringResource(R.string.photo_edit_reset),
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                ) {
                    OutlinedButton(enabled = !processing, onClick = onDismiss) {
                        Text(stringResource(R.string.common_cancel))
                    }
                    Button(
                        enabled = natural != null && !processing,
                        onClick = {
                            processing = true
                            scope.launch {
                                runCatching { transformAvatar(context, source, rotation, crop) }
                                    .onSuccess {
                                        onReady(it)
                                        onDismiss()
                                    }
                                    .onFailure {
                                        processing = false
                                        failed = true
                                    }
                            }
                        },
                    ) {
                        Text(stringResource(R.string.common_save))
                    }
                }
                if (failed) {
                    Text(
                        stringResource(R.string.photo_edit_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

private suspend fun transformAvatar(
    context: Context,
    source: Uri,
    rotation: Int,
    crop: CropBox,
): Uri =
    withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val orientation =
            resolver.openInputStream(source)?.use { ExifInterface(it).rotationDegrees } ?: 0
        val decoded =
            resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it) }
                ?: error("Image could not be decoded")
        val exif = resolver.openInputStream(source)?.use { ExifInterface(it) }
        val orient =
            Matrix().apply {
                postRotate(orientation.toFloat())
                if (exif?.isFlipped == true) postScale(-1f, 1f)
            }
        val upright =
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, orient, true)
        if (upright !== decoded) decoded.recycle()
        val turn = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(upright, 0, 0, upright.width, upright.height, turn, true)
        if (rotated !== upright) upright.recycle()
        val left = (crop.left * rotated.width).toInt().coerceIn(0, rotated.width - 1)
        val top = (crop.top * rotated.height).toInt().coerceIn(0, rotated.height - 1)
        val right = (crop.right * rotated.width).toInt().coerceIn(left + 1, rotated.width)
        val bottom = (crop.bottom * rotated.height).toInt().coerceIn(top + 1, rotated.height)
        val cropped = Bitmap.createBitmap(rotated, left, top, right - left, bottom - top)
        if (cropped !== rotated) rotated.recycle()
        val output = File.createTempFile("avatar-crop-", ".jpg", context.cacheDir)
        FileOutputStream(output).use { cropped.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        cropped.recycle()
        Uri.fromFile(output)
    }
