package blue.anika.wolle.ui.common

import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import blue.anika.wolle.R
import kotlin.math.roundToInt

/** State for the long-press-and-drag interaction used by both detail-page families. */
class DetailCardReorderState internal constructor() {
    var isReordering by mutableStateOf(false)
        private set

    private var draggingKey by mutableStateOf<String?>(null)
    private var dragOffset by mutableFloatStateOf(0f)

    fun begin(key: String) {
        isReordering = true
        draggingKey = key
        dragOffset = 0f
    }

    fun finish() {
        draggingKey = null
        dragOffset = 0f
    }

    internal fun offsetFor(key: String): IntOffset =
        if (key == draggingKey) IntOffset(0, dragOffset.roundToInt()) else IntOffset.Zero

    internal fun drag(
        key: String,
        deltaY: Float,
        listState: LazyListState,
        orderedKeys: List<String>,
        itemIndexOffset: Int,
        onMove: (fromIndex: Int, toIndex: Int) -> Unit,
    ) {
        if (!isReordering) begin(key)
        if (draggingKey != key) return
        dragOffset += deltaY

        val currentIndex = orderedKeys.indexOf(key)
        if (currentIndex < 0) return
        val currentInfo =
            listState.layoutInfo.visibleItemsInfo.firstOrNull {
                it.index == itemIndexOffset + currentIndex
            } ?: return
        val draggedCenter = currentInfo.offset + currentInfo.size / 2f + dragOffset
        val targetInfo =
            if (dragOffset > 0) {
                listState.layoutInfo.visibleItemsInfo
                    .filter { it.index > currentInfo.index }
                    .firstOrNull { draggedCenter > it.offset + it.size / 2f }
            } else {
                listState.layoutInfo.visibleItemsInfo
                    .filter { it.index < currentInfo.index }
                    .lastOrNull { draggedCenter < it.offset + it.size / 2f }
            }
        val targetIndex = targetInfo?.index?.minus(itemIndexOffset) ?: return
        if (targetIndex !in orderedKeys.indices || targetIndex == currentIndex) return

        onMove(currentIndex, targetIndex)
        dragOffset -= (targetInfo.offset - currentInfo.offset)
    }
}

@Composable
internal fun rememberDetailCardReorderState(): DetailCardReorderState = remember {
    DetailCardReorderState()
}

/** A Material 3 section card whose title is the drag handle for reorder mode. */
@Composable
internal fun ReorderableDetailCard(
    title: String,
    cardKey: String,
    cardIndex: Int,
    listState: LazyListState,
    orderedKeys: List<String>,
    itemIndexOffset: Int,
    reorderState: DetailCardReorderState,
    onMove: (fromIndex: Int, toIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    onEdit: (() -> Unit)? = null,
    copyText: String? = null,
    sticky: Boolean = false,
    icon: ImageVector? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
    content: @Composable ColumnScope.() -> Unit,
) {
    val titleDescription = stringResource(R.string.detail_card_reorder_title_description, title)
    val reorderDescription = stringResource(R.string.detail_card_reorder_long_press_description)
    val titleModifier =
        Modifier.fillMaxWidth()
            .pointerInput(cardKey, orderedKeys, itemIndexOffset) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { reorderState.begin(cardKey) },
                    onDrag = { change, dragAmount ->
                        reorderState.drag(
                            key = cardKey,
                            deltaY = dragAmount.y,
                            listState = listState,
                            orderedKeys = orderedKeys,
                            itemIndexOffset = itemIndexOffset,
                            onMove = onMove,
                        )
                    },
                    onDragEnd = reorderState::finish,
                    onDragCancel = reorderState::finish,
                )
            }
            .semantics {
                contentDescription = "$titleDescription $reorderDescription"
                role = Role.Button
                stateDescription =
                    if (reorderState.isReordering) reorderDescription else titleDescription
                onLongClick(label = reorderDescription) {
                    reorderState.begin(cardKey)
                    true
                }
            }

    val cardModifier =
        modifier
            .fillMaxWidth()
            .offset { reorderState.offsetFor(cardKey) }
            .zIndex(if (reorderState.offsetFor(cardKey) != IntOffset.Zero) 1f else 0f)
    val body: @Composable ColumnScope.() -> Unit = {
        Row(
            modifier = titleModifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (icon != null) {
                // The same icon chip as Home's SectionTitle, so a card heading reads alike.
                Box(
                    Modifier.size(30.dp).clip(CircleShape).background(accent.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (reorderState.isReordering) {
                Icon(
                    Icons.Filled.DragHandle,
                    contentDescription = reorderDescription,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (onEdit == null) {
            content()
        } else {
            // Long-pressing the title reorders the cards; long-pressing the content opens the
            // Edit / Copy text menu for this section.
            SectionActions(
                what = title,
                copyText = copyText,
                onEdit = onEdit,
                content = content,
            )
        }
    }
    if (sticky) {
        StickyNoteSurface(modifier = cardModifier, content = body)
    } else {
        Card(
            modifier = cardModifier,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            colors =
                CardDefaults.cardColors(
                    containerColor = containerColor,
                    contentColor = contentColor,
                ),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                content = body,
            )
        }
    }
}

/** Persistent list slot kept in place so drag coordinates remain stable as mode changes. */
@Composable
internal fun DetailCardReorderHint(
    reorderState: DetailCardReorderState,
    onDone: () -> Unit,
) {
    // Shown only while reordering. A permanent "long-press to reorder" tip sat above every detail
    // page's content; the card titles' accessibility labels still announce the long-press.
    AnimatedVisibility(
        visible = reorderState.isReordering,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        DetailCardReorderBar(onDone)
    }
}

@Composable
private fun DetailCardReorderBar(onDone: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.detail_card_reorder_active),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            androidx.compose.material3.TextButton(onClick = onDone) {
                Text(stringResource(R.string.detail_card_reorder_done))
            }
        }
    }
}

/**
 * Long-press on a section or step opens a small menu at the press point: Edit (the editor at this
 * section) and, when there is text, Copy text. A haptic tick confirms the press; copying confirms
 * itself (Android 13+ shows its clipboard note, older versions get a toast). TalkBack offers both
 * as named actions.
 */
@Composable
fun SectionActions(
    what: String,
    copyText: String?,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val editLabel = stringResource(R.string.detail_long_press_edit_action, what)
    val copyLabel = stringResource(R.string.detail_long_press_copy_action)
    val copiedMessage = stringResource(R.string.detail_long_press_copied, what)
    var menuOffset by remember { mutableStateOf<DpOffset?>(null) }
    var height by remember { mutableIntStateOf(0) }
    val copy: (String) -> Unit = { text ->
        clipboard.setText(AnnotatedString(text))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
        }
    }
    Box(modifier.fillMaxWidth().onSizeChanged { height = it.height }) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .longPressAboveChildren { position ->
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        // DropdownMenu offsets are measured from the anchor's bottom edge.
                        menuOffset =
                            with(density) {
                                DpOffset(position.x.toDp(), (position.y - height).toDp())
                            }
                    }
                    .semantics {
                        customActions =
                            listOfNotNull(
                                CustomAccessibilityAction(editLabel) {
                                    onEdit()
                                    true
                                },
                                copyText?.let { text ->
                                    CustomAccessibilityAction(copyLabel) {
                                        copy(text)
                                        true
                                    }
                                },
                            )
                    },
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = content,
        )
        DropdownMenu(
            expanded = menuOffset != null,
            onDismissRequest = { menuOffset = null },
            offset = menuOffset ?: DpOffset.Zero,
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.common_edit)) },
                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                onClick = {
                    menuOffset = null
                    onEdit()
                },
            )
            if (!copyText.isNullOrBlank()) {
                DropdownMenuItem(
                    text = { Text(copyLabel) },
                    leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                    onClick = {
                        menuOffset = null
                        copy(copyText)
                    },
                )
            }
        }
    }
}

/** "Label: value" lines for the fields that have a value - the copy text of a details card. */
internal fun copyLines(vararg fields: Pair<String, String?>): String =
    fields
        .filter { (_, value) -> !value.isNullOrBlank() }
        .joinToString("\n") { (label, value) -> "$label: $value" }

/**
 * Long-press detection that also works on top of tappable content (Markdown images that open the
 * viewer, linked yarn or project rows, attachments). A plain `detectTapGestures` never sees those
 * presses, because the child's click handler claims the touch first. This watches the gesture in
 * the Initial pass, before the children, without consuming anything: taps and scrolls reach them as
 * usual. Only once the long-press fires is the rest of that gesture consumed, so the child does not
 * also treat its release as a tap.
 */
internal fun Modifier.longPressAboveChildren(onLongPress: (Offset) -> Unit): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val ended =
                withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val moved = (change.position - down.position).getDistance()
                        if (!change.pressed || moved > viewConfiguration.touchSlop) break
                    }
                }
            // null means the timeout passed with the finger still down and in place.
            if (ended == null) {
                onLongPress(down.position)
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            }
        }
    }
