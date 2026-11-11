package blue.anika.wolle.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSeparator
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSession
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.CompletableDeferred

@Composable
internal fun MarkdownSelectionMenu(content: @Composable () -> Unit) {
    val provider = remember { MarkdownTextContextMenuProvider() }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

    CompositionLocalProvider(LocalTextContextMenuToolbarProvider provides provider) {
        DisposableEffect(provider) { onDispose(provider::cancel) }
        androidx.compose.foundation.layout.Box(Modifier.onGloballyPositioned { coordinates = it }) {
            content()
            provider.Menu { coordinates }
        }
    }
}

private class MarkdownTextContextMenuProvider : TextContextMenuProvider {
    private var active by mutableStateOf<ActiveMenu?>(null)

    fun cancel() {
        active?.close()
    }

    override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
        val menu = ActiveMenu(dataProvider)
        active?.close()
        active = menu
        try {
            menu.closed.await()
        } finally {
            if (active === menu) active = null
        }
    }

    @Composable
    fun Menu(anchorCoordinates: () -> LayoutCoordinates?) {
        val menu = active ?: return
        val coordinates = anchorCoordinates() ?: return
        if (!coordinates.isAttached) return
        Popup(
            popupPositionProvider = MarkdownMenuPositionProvider(menu.dataProvider, coordinates),
            onDismissRequest = menu::close,
            properties = PopupProperties(focusable = true),
        ) {
            Column(
                Modifier.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                    .widthIn(max = 320.dp)
                    .width(IntrinsicSize.Max)
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp)
            ) {
                menu.dataProvider.data().components.forEachIndexed { index, component ->
                    when (component) {
                        is TextContextMenuItem ->
                            DropdownMenuItem(
                                text = { Text(component.label) },
                                leadingIcon =
                                    if (component.leadingIcon != 0) {
                                        {
                                            Icon(
                                                painter = painterResource(component.leadingIcon),
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp),
                                                tint = MaterialTheme.colorScheme.onSurface,
                                            )
                                        }
                                    } else null,
                                onClick = { menu.onClick(component) },
                            )
                        TextContextMenuSeparator -> if (index > 0) HorizontalDivider()
                        else -> Unit
                    }
                }
            }
        }
    }
}

private class ActiveMenu(val dataProvider: TextContextMenuDataProvider) : TextContextMenuSession {
    val closed = CompletableDeferred<Unit>()

    override fun close() {
        closed.complete(Unit)
    }

    fun onClick(item: TextContextMenuItem) {
        try {
            item.onClick(this)
        } finally {
            close()
        }
    }
}

private class MarkdownMenuPositionProvider(
    private val dataProvider: TextContextMenuDataProvider,
    private val anchor: LayoutCoordinates,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val local = dataProvider.position(anchor)
        val window = anchor.localToWindow(local)
        val x =
            window.x
                .toInt()
                .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val above = window.y.toInt() - popupContentSize.height
        val y =
            (if (above >= 0) above else window.y.toInt()).coerceIn(
                0,
                (windowSize.height - popupContentSize.height).coerceAtLeast(0),
            )
        return IntOffset(x, y)
    }
}
