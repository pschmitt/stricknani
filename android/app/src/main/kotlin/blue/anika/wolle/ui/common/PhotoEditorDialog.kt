package blue.anika.wolle.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import blue.anika.wolle.R
import blue.anika.wolle.data.api.v2.PhotoAltRequest
import blue.anika.wolle.data.api.v2.PhotoEditRequest
import blue.anika.wolle.data.api.v2.PhotoEditV2Api
import blue.anika.wolle.data.repository.ProjectRepository
import blue.anika.wolle.data.repository.YarnRepository
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The saved photo the editor works on: a project image (title or step) or a yarn photo. */
sealed interface PhotoEditTarget {
    data class ProjectImage(val projectId: Int, val imageId: Int) : PhotoEditTarget

    data class YarnPhoto(val yarnId: Int, val photoId: Int) : PhotoEditTarget
}

data class PhotoEditUiState(val saving: Boolean = false, val failed: Boolean = false)

/** What a save produced: the photo's current URLs and, when it was changed, its description. */
data class PhotoEditSaved(val url: String, val thumbnailUrl: String, val altText: String?)

/**
 * Sends the edit to the server (it needs the network, so it is never queued), then refreshes the
 * project or yarn so every view and the offline cache show the new picture.
 */
@HiltViewModel
class PhotoEditViewModel
@Inject
constructor(
    private val api: PhotoEditV2Api,
    private val projects: ProjectRepository,
    private val yarns: YarnRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(PhotoEditUiState())
    val state: StateFlow<PhotoEditUiState> = _state.asStateFlow()

    fun reset() {
        _state.value = PhotoEditUiState()
    }

    /**
     * Saves the new description ([altText], when changed) and then the rotation and crop
     * ([request], when edited); calls [onSaved] with the photo's current URLs once the server
     * accepted everything. A failure after the description was stored still refreshes the screens.
     */
    fun save(
        target: PhotoEditTarget,
        request: PhotoEditRequest?,
        altText: String?,
        onSaved: (PhotoEditSaved) -> Unit,
    ) {
        if (_state.value.saving) return
        if (request == null && altText == null) return
        _state.value = PhotoEditUiState(saving = true)
        viewModelScope.launch {
            var altStored = false
            try {
                var current: Pair<String, String>? = null
                var storedAlt: String? = null
                if (altText != null) {
                    val body = PhotoAltRequest(altText.trim())
                    when (target) {
                        is PhotoEditTarget.ProjectImage ->
                            api.updateProjectImageAlt(target.projectId, target.imageId, body)
                                .data
                                .let {
                                    current = it.url to it.thumbnailUrl
                                    storedAlt = it.altText
                                }
                        is PhotoEditTarget.YarnPhoto ->
                            api.updateYarnPhotoAlt(target.yarnId, target.photoId, body).data.let {
                                current = it.url to it.thumbnailUrl
                                storedAlt = it.altText
                            }
                    }
                    altStored = true
                }
                if (request != null) {
                    when (target) {
                        is PhotoEditTarget.ProjectImage ->
                            api.editProjectImage(target.projectId, target.imageId, request)
                                .data
                                .let { current = it.url to it.thumbnailUrl }
                        is PhotoEditTarget.YarnPhoto ->
                            api.editYarnPhoto(target.yarnId, target.photoId, request).data.let {
                                current = it.url to it.thumbnailUrl
                            }
                    }
                }
                val (url, thumbnail) = current ?: return@launch
                // The picture is saved; a failed refresh only delays the new thumbnails.
                try {
                    when (target) {
                        is PhotoEditTarget.ProjectImage -> projects.refreshOne(target.projectId)
                        is PhotoEditTarget.YarnPhoto -> yarns.refreshOne(target.yarnId)
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {}
                _state.value = PhotoEditUiState()
                onSaved(PhotoEditSaved(url, thumbnail, storedAlt))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                if (altStored) {
                    try {
                        when (target) {
                            is PhotoEditTarget.ProjectImage -> projects.refreshOne(target.projectId)
                            is PhotoEditTarget.YarnPhoto -> yarns.refreshOne(target.yarnId)
                        }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {}
                }
                _state.update { it.copy(saving = false, failed = true) }
            }
        }
    }
}

private val HandleTouchRadius = 24.dp
private val EdgePad = 24.dp

/**
 * The lightweight photo editor: the original [imageUrl] turned in quarter steps with a crop box
 * over the turned picture (drag the corners, or the box), and a Description field ([altText] is the
 * stored one). Save sends the description, the rotation and the crop to the server and calls
 * [onSaved] with the current URLs and the stored description; on a failure the dialog stays open.
 */
@Composable
fun PhotoEditorDialog(
    target: PhotoEditTarget,
    imageUrl: String,
    onDismiss: () -> Unit,
    altText: String = "",
    onSaved: (PhotoEditSaved) -> Unit = {},
) {
    val viewModel: PhotoEditViewModel = hiltViewModel(key = "photo-edit-$target")
    LaunchedEffect(target) { viewModel.reset() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var rotation by remember { mutableIntStateOf(0) }
    var crop by remember { mutableStateOf(CropBox.Whole) }
    val request = PhotoEditMath.request(rotation, crop)
    var description by rememberSaveable(target) { mutableStateOf(altText) }
    val altChanged = PhotoEditMath.altChanged(altText, description)
    val painter = rememberAsyncImagePainter(model = imageUrl)
    val painterState by painter.state.collectAsStateWithLifecycle()
    val natural =
        (painterState as? AsyncImagePainter.State.Success)?.result?.image?.let {
            if (it.width > 0 && it.height > 0) it.width to it.height else null
        }

    Dialog(
        onDismissRequest = { if (!state.saving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier =
                    Modifier.fillMaxSize()
                        .imePadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
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
                    modifier =
                        Modifier.weight(1f)
                            .fillMaxWidth()
                            .background(Color(0xFF14141A), MaterialTheme.shapes.large),
                    contentAlignment = Alignment.Center,
                ) {
                    if (natural == null) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    } else {
                        CropStage(
                            painter = painter,
                            natural = natural,
                            rotation = rotation,
                            crop = crop,
                            enabled = !state.saving,
                            onCropChange = { crop = it },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ToolButton(
                        Icons.AutoMirrored.Filled.RotateLeft,
                        stringResource(R.string.photo_edit_rotate_left),
                        enabled = natural != null && !state.saving,
                        modifier = Modifier.weight(1f),
                    ) {
                        rotation = PhotoEditMath.rotateBy(rotation, -1)
                        crop = PhotoEditMath.rotateCrop(crop, -1)
                    }
                    ToolButton(
                        Icons.AutoMirrored.Filled.RotateRight,
                        stringResource(R.string.photo_edit_rotate_right),
                        enabled = natural != null && !state.saving,
                        modifier = Modifier.weight(1f),
                    ) {
                        rotation = PhotoEditMath.rotateBy(rotation, 1)
                        crop = PhotoEditMath.rotateCrop(crop, 1)
                    }
                    ToolButton(
                        Icons.Filled.Restore,
                        stringResource(R.string.photo_edit_reset),
                        enabled = request != null && !state.saving,
                        modifier = Modifier.weight(1f),
                    ) {
                        rotation = 0
                        crop = CropBox.Whole
                    }
                }
                OutlinedTextField(
                    value = description,
                    onValueChange = {
                        if (it.length <= PhotoEditMath.MAX_ALT_LENGTH) description = it
                    },
                    label = { Text(stringResource(R.string.photo_edit_description)) },
                    supportingText = {
                        Text(stringResource(R.string.photo_edit_description_hint))
                    },
                    enabled = !state.saving,
                    singleLine = true,
                    keyboardOptions =
                        KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Done,
                        ),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.failed) {
                    Text(
                        stringResource(R.string.photo_edit_failed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                    )
                }
                if (state.saving) {
                    Row(
                        modifier =
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                                liveRegion = LiveRegionMode.Polite
                            },
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 3.dp,
                        )
                        Text(
                            stringResource(R.string.photo_edit_saving),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.common_cancel))
                        }
                        Button(
                            onClick = {
                                viewModel.save(
                                    target,
                                    request,
                                    description.takeIf { altChanged },
                                ) { saved ->
                                    onSaved(saved)
                                    onDismiss()
                                }
                            },
                            enabled = request != null || altChanged,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.common_save))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val tint =
        if (enabled) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Column(
        modifier =
            modifier
                .heightIn(min = 56.dp)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = tint,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

/**
 * The rotated picture fitted into the available space with the crop box over it. [crop] is in
 * fractions of the rotated picture; the gestures turn pixel drags into fractions through
 * [PhotoEditMath].
 */
@Composable
internal fun CropStage(
    painter: AsyncImagePainter,
    natural: Pair<Int, Int>,
    rotation: Int,
    crop: CropBox,
    enabled: Boolean,
    onCropChange: (CropBox) -> Unit,
) {
    val density = LocalDensity.current
    val primary = MaterialTheme.colorScheme.primary
    val currentCrop by rememberUpdatedState(crop)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentChange by rememberUpdatedState(onCropChange)
    BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val padPx = with(density) { EdgePad.toPx() }
        val availW = (constraints.maxWidth - 2 * padPx).coerceAtLeast(1f)
        val availH = (constraints.maxHeight - 2 * padPx).coerceAtLeast(1f)
        val (rotW, rotH) = PhotoEditMath.rotatedSize(natural.first, natural.second, rotation)
        val scale = minOf(availW / rotW, availH / rotH)
        val dispW = rotW * scale
        val dispH = rotH * scale
        val unrotW = natural.first * scale
        val unrotH = natural.second * scale
        val minW = PhotoEditMath.minFraction(rotW)
        val minH = PhotoEditMath.minFraction(rotH)
        val touchRadiusPx = with(density) { HandleTouchRadius.toPx() }
        var drag by remember { mutableStateOf<CropDrag?>(null) }
        Box(
            modifier =
                Modifier.size(
                        with(density) { (dispW + 2 * padPx).toDp() },
                        with(density) { (dispH + 2 * padPx).toDp() },
                    )
                    .pointerInput(dispW, dispH, rotation) {
                        detectDragGestures(
                            onDragStart = { start ->
                                drag =
                                    if (!currentEnabled) null
                                    else
                                        PhotoEditMath.hitTest(
                                            currentCrop,
                                            start.x - padPx,
                                            start.y - padPx,
                                            dispW,
                                            dispH,
                                            touchRadiusPx,
                                        )
                            },
                            onDragEnd = { drag = null },
                            onDragCancel = { drag = null },
                        ) { change, amount ->
                            val mode = drag ?: return@detectDragGestures
                            change.consume()
                            val dx = amount.x / dispW
                            val dy = amount.y / dispH
                            currentChange(
                                when (mode) {
                                    is CropDrag.Corner ->
                                        PhotoEditMath.moveCorner(
                                            currentCrop,
                                            mode.corner,
                                            dx,
                                            dy,
                                            minW,
                                            minH,
                                        )
                                    CropDrag.Move -> PhotoEditMath.moveBox(currentCrop, dx, dy)
                                }
                            )
                        }
                    },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painter,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier =
                    Modifier.requiredSize(
                            with(density) { unrotW.toDp() },
                            with(density) { unrotH.toDp() },
                        )
                        .graphicsLayer { rotationZ = rotation.toFloat() },
            )
            Canvas(modifier = Modifier.fillMaxSize()) {
                val left = padPx + crop.left * dispW
                val top = padPx + crop.top * dispH
                val right = padPx + crop.right * dispW
                val bottom = padPx + crop.bottom * dispH
                val dim = Color.Black.copy(alpha = 0.55f)
                // Dim everything outside the box: four bands around it.
                drawRect(dim, Offset(padPx, padPx), Size(dispW, top - padPx))
                drawRect(dim, Offset(padPx, bottom), Size(dispW, padPx + dispH - bottom))
                drawRect(dim, Offset(padPx, top), Size(left - padPx, bottom - top))
                drawRect(dim, Offset(right, top), Size(padPx + dispW - right, bottom - top))
                val thin = 1.dp.toPx()
                val grid = Color.White.copy(alpha = 0.4f)
                for (i in 1..2) {
                    val gx = left + (right - left) * i / 3f
                    val gy = top + (bottom - top) * i / 3f
                    drawLine(grid, Offset(gx, top), Offset(gx, bottom), thin)
                    drawLine(grid, Offset(left, gy), Offset(right, gy), thin)
                }
                drawRect(
                    Color.White,
                    Offset(left, top),
                    Size(right - left, bottom - top),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()),
                )
                listOf(
                        Offset(left, top),
                        Offset(right, top),
                        Offset(left, bottom),
                        Offset(right, bottom),
                    )
                    .forEach { corner ->
                        drawCircle(Color.White, 11.dp.toPx(), corner)
                        drawCircle(primary, 8.dp.toPx(), corner)
                    }
            }
        }
    }
}
