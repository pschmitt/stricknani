package blue.anika.wolle.ui.common

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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

    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .offset { reorderState.offsetFor(cardKey) }
                .zIndex(if (reorderState.offsetFor(cardKey) != IntOffset.Zero) 1f else 0f),
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
        ) {
            Row(modifier = titleModifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            content()
        }
    }
}

/** Persistent list slot kept in place so drag coordinates remain stable as mode changes. */
@Composable
internal fun DetailCardReorderHint(
    reorderState: DetailCardReorderState,
    onDone: () -> Unit,
) {
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
                text =
                    stringResource(
                        if (reorderState.isReordering) R.string.detail_card_reorder_active
                        else R.string.detail_card_reorder_hint
                    ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (reorderState.isReordering) {
                androidx.compose.material3.TextButton(onClick = onDone) {
                    Text(stringResource(R.string.detail_card_reorder_done))
                }
            }
        }
    }
}
