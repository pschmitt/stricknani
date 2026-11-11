@file:OptIn(
    ExperimentalRichTextApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package blue.anika.wolle.ui.common

import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import blue.anika.wolle.R
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.model.HeadingStyle
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.ui.BasicRichTextEditor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * A Markdown text field, the Android counterpart of the web's Markdown editor: a formatting toolbar
 * (bold, italic, heading, lists, link) above either the visual editor (rich text, edited in place)
 * or the Markdown source ("Raw"), chosen with a Visual / Raw toggle that is remembered for every
 * field. The value stays the Markdown string, so drafts, dirty tracking and saving behave exactly
 * as with a plain text field.
 *
 * Nothing is mangled: before the visual editor opens a text it converts it to rich text and back
 * and compares the result (see [MarkdownRich.canEditVisually]); a text the editor cannot carry
 * losslessly (tables, quotes, code, ...) stays in Raw with a hint. Writing escapes literal Markdown
 * characters, so no edit is ever refused. The field emits Markdown only when the user edits, so
 * opening a field never makes a form dirty.
 */
@Composable
internal fun MarkdownTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    fieldModifier: Modifier = Modifier,
    labelIcon: ImageVector? = null,
    minLines: Int = 3,
    keepLineBreaks: Boolean = false,
    keyboardOptions: KeyboardOptions =
        KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
) {
    val currentValue by rememberUpdatedState(value)
    val currentOnChange by rememberUpdatedState(onValueChange)

    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    val rich = remember { RichTextState().also(::registerPictureTriggers) }
    val thumbLineHeight = with(LocalDensity.current) { ThumbnailGeometry.LINE_HEIGHT_DP.dp.toSp() }
    val currentThumbLineHeight by rememberUpdatedState(thumbLineHeight)
    // The Markdown the editor currently stands for, to tell outside changes from its own.
    var known by remember { mutableStateOf(value) }
    // What this field emitted and the caller has not handed back yet. The caller's value arrives
    // asynchronously, so while the user types fast it echoes older strings; those must not count as
    // outside changes (they would reset the editor and drop typed characters).
    val echoes = remember { EmitEchoes() }

    fun emit(text: String) {
        known = text
        echoes.record(text)
        currentOnChange(text)
    }
    // What the visual editor last wrote, so that only real edits are emitted.
    var written by remember { mutableStateOf("") }
    var unsupported by remember { mutableStateOf(false) }
    var visual by remember { mutableStateOf(false) }

    fun enterVisual(): Boolean {
        val ok = MarkdownRich.canEditVisually(currentValue, keepLineBreaks)
        if (ok) {
            MarkdownRich.load(rich, currentValue, currentThumbLineHeight, keepLineBreaks)
            written = MarkdownRich.toMarkdown(rich, keepLineBreaks)
        }
        unsupported = !ok
        return ok
    }

    // The first frame already shows the right editor.
    remember {
        // Every field starts in Visual (Raw only for text it cannot show) and switches on its own.
        visual = enterVisual()
        true
    }
    // Outside changes of the text (a restored draft, a reload).
    LaunchedEffect(value) {
        if (value == known) {
            echoes.clear()
            return@LaunchedEffect
        }
        if (echoes.isEcho(value)) return@LaunchedEffect
        known = value
        if (visual) {
            if (MarkdownRich.canEditVisually(value, keepLineBreaks)) {
                MarkdownRich.load(rich, value, currentThumbLineHeight, keepLineBreaks)
                written = MarkdownRich.toMarkdown(rich, keepLineBreaks)
            } else {
                unsupported = true
                visual = false
            }
        }
        if (value != field.text) field = TextFieldValue(value, TextRange(value.length))
    }

    // Edits of the visual editor become Markdown.
    LaunchedEffect(visual) {
        if (!visual) return@LaunchedEffect
        snapshotFlow { rich.annotatedString }
            .collect {
                // A half-deleted picture goes whole, and a picture takes the form (thumbnail or
                // chip) its place calls for; each change comes back as a new value.
                if (MarkdownRich.dropDamagedPictures(rich)) return@collect
                if (MarkdownRich.relayoutPictures(rich, currentThumbLineHeight)) return@collect
                val markdown = MarkdownRich.toMarkdown(rich, keepLineBreaks)
                if (markdown == written) return@collect
                written = markdown
                // Keep the Raw field in step, so switching modes shows the edited text.
                field = TextFieldValue(markdown, TextRange(markdown.length))
                emit(markdown)
            }
    }

    val tint = LocalContentColor.current
    val context = LocalContext.current
    val unsupportedMessage = stringResource(R.string.markdown_editor_unsupported)
    var linkOpen by remember { mutableStateOf(false) }
    var linkValue by remember { mutableStateOf("") }
    var linkHadHref by remember { mutableStateOf(false) }
    var linkRange by remember { mutableStateOf(TextRange.Zero) }

    // The same formatting as the toolbar, for the selection's context menu.
    fun applyFormat(format: MarkdownFormat) {
        if (!visual) {
            applyRaw(field, format, ::emit, { known = it }) { field = it }
            return
        }
        when (format) {
            MarkdownFormat.BOLD -> rich.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold))
            MarkdownFormat.ITALIC -> rich.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic))
            MarkdownFormat.HEADING ->
                rich.setHeadingStyle(
                    if (rich.currentHeadingStyle != HeadingStyle.Normal) HeadingStyle.Normal
                    else HeadingStyle.H3
                )
            MarkdownFormat.BULLET_LIST -> rich.toggleUnorderedList()
            MarkdownFormat.NUMBERED_LIST -> rich.toggleOrderedList()
            MarkdownFormat.LINK -> {
                linkHadHref = rich.isLink
                linkValue = rich.selectedLinkUrl.orEmpty()
                linkRange = rich.selection
                linkOpen = true
            }
        }
    }
    val menuItems =
        listOf(
            Triple(
                MarkdownFormat.BOLD,
                stringResource(R.string.markdown_editor_bold),
                R.drawable.markdown_format_bold,
            ),
            Triple(
                MarkdownFormat.ITALIC,
                stringResource(R.string.markdown_editor_italic),
                R.drawable.markdown_format_italic,
            ),
            Triple(
                MarkdownFormat.HEADING,
                stringResource(R.string.markdown_editor_heading),
                R.drawable.markdown_title,
            ),
            Triple(
                MarkdownFormat.BULLET_LIST,
                stringResource(R.string.markdown_editor_bullet_list),
                R.drawable.markdown_format_list_bulleted,
            ),
            Triple(
                MarkdownFormat.NUMBERED_LIST,
                stringResource(R.string.markdown_editor_numbered_list),
                R.drawable.markdown_format_list_numbered,
            ),
            Triple(
                MarkdownFormat.LINK,
                stringResource(R.string.markdown_editor_link),
                R.drawable.markdown_link,
            ),
        )
    // Bold and Italic first, right after the system's Cut / Copy / Paste.
    val formatMenu = Modifier.appendTextContextMenuComponents {
        separator()
        for ((format, menuLabel, icon) in menuItems) {
            item(
                key = "markdown-${format.name}",
                label = menuLabel,
                leadingIcon = icon,
            ) {
                close()
                applyFormat(format)
            }
        }
        separator()
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val formattingDescription = stringResource(R.string.markdown_editor_formatting)
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()).semantics {
                    contentDescription = formattingDescription
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolbarButton(
                    Icons.Filled.FormatBold,
                    R.string.markdown_editor_bold,
                    tint,
                    active = visual && (rich.currentSpanStyle.fontWeight?.weight ?: 400) > 400,
                ) {
                    if (visual) {
                        rich.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    } else {
                        applyRaw(field, MarkdownFormat.BOLD, ::emit, { known = it }) {
                            field = it
                        }
                    }
                }
                ToolbarButton(
                    Icons.Filled.FormatItalic,
                    R.string.markdown_editor_italic,
                    tint,
                    active = visual && rich.currentSpanStyle.fontStyle == FontStyle.Italic,
                ) {
                    if (visual) {
                        rich.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    } else {
                        applyRaw(field, MarkdownFormat.ITALIC, ::emit, { known = it }) {
                            field = it
                        }
                    }
                }
                ToolbarButton(
                    Icons.Filled.Title,
                    R.string.markdown_editor_heading,
                    tint,
                    active = visual && rich.currentHeadingStyle != HeadingStyle.Normal,
                ) {
                    if (visual) {
                        rich.setHeadingStyle(
                            if (rich.currentHeadingStyle != HeadingStyle.Normal) {
                                HeadingStyle.Normal
                            } else {
                                HeadingStyle.H3
                            }
                        )
                    } else {
                        applyRaw(field, MarkdownFormat.HEADING, ::emit, { known = it }) {
                            field = it
                        }
                    }
                }
                ToolbarButton(
                    Icons.AutoMirrored.Filled.FormatListBulleted,
                    R.string.markdown_editor_bullet_list,
                    tint,
                    active = visual && rich.isUnorderedList,
                ) {
                    if (visual) {
                        rich.toggleUnorderedList()
                    } else {
                        applyRaw(
                            field,
                            MarkdownFormat.BULLET_LIST,
                            ::emit,
                            { known = it },
                        ) {
                            field = it
                        }
                    }
                }
                ToolbarButton(
                    Icons.Filled.FormatListNumbered,
                    R.string.markdown_editor_numbered_list,
                    tint,
                    active = visual && rich.isOrderedList,
                ) {
                    if (visual) {
                        rich.toggleOrderedList()
                    } else {
                        applyRaw(
                            field,
                            MarkdownFormat.NUMBERED_LIST,
                            ::emit,
                            { known = it },
                        ) {
                            field = it
                        }
                    }
                }
                ToolbarButton(
                    Icons.Filled.Link,
                    R.string.markdown_editor_link,
                    tint,
                    active = visual && rich.isLink,
                ) {
                    if (visual) {
                        linkHadHref = rich.isLink
                        linkValue = rich.selectedLinkUrl.orEmpty()
                        linkRange = rich.selection
                        linkOpen = true
                    } else {
                        applyRaw(field, MarkdownFormat.LINK, ::emit, { known = it }) {
                            field = it
                        }
                    }
                }
            }
            VerticalDivider(Modifier.height(24.dp).padding(horizontal = 4.dp))
            ToolbarButton(
                MdiIcons.Markdown,
                R.string.markdown_editor_edit_as_markdown,
                tint,
                active = !visual,
            ) {
                if (!visual) {
                    if (MarkdownRich.canEditVisually(value, keepLineBreaks)) {
                        visual = enterVisual()
                    } else {
                        // Never a silent no-op: say why the text stays in Markdown.
                        unsupported = true
                        Toast.makeText(context, unsupportedMessage, Toast.LENGTH_LONG).show()
                    }
                } else {
                    visual = false
                }
            }
        }
        if (visual && linkOpen) {
            LinkRow(
                value = linkValue,
                onValueChange = { linkValue = it },
                hadLink = linkHadHref,
                onApply = {
                    val url = normalizeLinkTarget(linkValue)
                    if (url.isEmpty()) {
                        rich.removeLink()
                    } else if (rich.isLink) {
                        rich.updateLink(url)
                    } else if (!linkRange.collapsed) {
                        rich.addLinkToTextRange(url, linkRange)
                    } else {
                        rich.addLink(url, url)
                    }
                    linkOpen = false
                },
                onRemove = {
                    rich.removeLink()
                    linkOpen = false
                },
                onCancel = { linkOpen = false },
            )
        }
        if (visual) {
            VisualEditor(
                state = rich,
                label = label,
                labelIcon = labelIcon,
                minLines = minLines,
                modifier = fieldModifier.then(formatMenu),
                keyboardOptions = keyboardOptions,
                colors = colors,
            )
        } else {
            OutlinedTextField(
                value = field,
                onValueChange = {
                    field = it
                    if (it.text != currentValue) {
                        emit(it.text)
                    }
                },
                label = { FieldLabel(label, labelIcon) },
                modifier = fieldModifier.then(formatMenu).fillMaxWidth(),
                minLines = minLines,
                keyboardOptions = keyboardOptions,
                colors = colors,
            )
            if (unsupported) {
                Text(
                    stringResource(R.string.markdown_editor_unsupported),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = 0.75f),
                )
            }
        }
    }
}

/** A link address as the web editor normalizes it: web links get `https://` when it is missing. */
internal fun normalizeLinkTarget(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    val known = Regex("^(https?:|mailto:|tel:|/|#)", RegexOption.IGNORE_CASE)
    return if (known.containsMatchIn(trimmed)) trimmed else "https://$trimmed"
}

private fun applyRaw(
    field: TextFieldValue,
    format: MarkdownFormat,
    onValueChange: (String) -> Unit,
    setKnown: (String) -> Unit,
    setField: (TextFieldValue) -> Unit,
) {
    val next = applyMarkdownFormat(field, format)
    setField(next)
    if (next.text != field.text) {
        setKnown(next.text)
        onValueChange(next.text)
    }
}

/**
 * The rich text surface: the outlined text field look (label, border), with the reader's body text
 * so that editing looks like viewing.
 */
@Composable
private fun VisualEditor(
    state: RichTextState,
    label: String,
    labelIcon: ImageVector?,
    minLines: Int,
    modifier: Modifier,
    keyboardOptions: KeyboardOptions,
    colors: TextFieldColors,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val ink =
        colors.focusedTextColor.takeIf { it != Color.Unspecified } ?: LocalContentColor.current
    val border =
        if (focused) colors.focusedIndicatorColor.orElse(ink)
        else colors.unfocusedIndicatorColor.orElse(ink.copy(alpha = 0.55f))
    val labelColor =
        if (focused) colors.focusedLabelColor.orElse(ink)
        else colors.unfocusedLabelColor.orElse(ink)
    val link = MaterialTheme.colorScheme.primary
    val host = remember(state) { ThumbnailHost(state) }
    val measurer = rememberTextMeasurer()
    val scope = rememberCoroutineScope()
    SideEffect { host.focused = focused }
    // Opening the keyboard can scroll the form so far that the selection ends up off screen
    // (long texts, long-press to select). Once the keyboard has settled, show the selection again.
    val selectionRequester = remember { BringIntoViewRequester() }
    val imeInsets = WindowInsets.ime
    val density = LocalDensity.current
    LaunchedEffect(focused) {
        if (!focused) return@LaunchedEffect
        snapshotFlow { imeInsets.getBottom(density) }
            .distinctUntilChanged()
            .collectLatest { bottom ->
                if (bottom == 0) return@collectLatest
                delay(KEYBOARD_SETTLE_MS)
                val layout = host.layout ?: return@collectLatest
                val length = layout.layoutInput.text.length
                val selection = state.selection
                val first = layout.getCursorRect(selection.min.coerceIn(0, length))
                val last = layout.getCursorRect(selection.max.coerceIn(0, length))
                val margin = with(density) { 48.dp.toPx() }
                // A selection taller than the screen: its start is what matters.
                val bottomEdge = minOf(last.bottom, first.top + with(density) { 240.dp.toPx() })
                selectionRequester.bringIntoView(
                    Rect(0f, first.top - margin, layout.size.width.toFloat(), bottomEdge + margin)
                )
            }
    }
    host.paddingPx = with(LocalDensity.current) { 4.dp.toPx() }
    LaunchedEffect(state) {
        snapshotFlow { state.annotatedString }
            .collect { host.pictures = MarkdownRich.thumbnails(state) }
    }
    host.images = rememberThumbnailPainters(host.pictures.map { it.image })
    val caretColor = colors.cursorColor.orElse(ink)
    // A thumbnail's line is tall, and so would its caret be: the outline shows the position.
    val cursor = if (host.caretBesidePicture()) Color.Transparent else caretColor
    // Not in the composition body: the library re-lays out its text when a config value is set,
    // which would reset the selection on every recomposition.
    LaunchedEffect(link) { if (state.config.linkColor != link) state.config.linkColor = link }
    Column(
        Modifier.fillMaxWidth()
            .border(if (focused) 2.dp else 1.dp, border, RoundedCornerShape(4.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        CompositionLocalProvider(
            LocalContentColor provides labelColor,
            LocalTextStyle provides MaterialTheme.typography.labelMedium,
        ) {
            FieldLabel(label, labelIcon)
        }
        BasicRichTextEditor(
            state = state,
            modifier =
                modifier
                    .fillMaxWidth()
                    .bringIntoViewRequester(selectionRequester)
                    .pictureThumbnails(
                        host = host,
                        measurer = measurer,
                        placeholder = MaterialTheme.colorScheme.surfaceVariant,
                        onPlaceholder = MaterialTheme.colorScheme.onSurfaceVariant,
                        outline = MaterialTheme.colorScheme.primary,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        onTap = { offset ->
                            // After the editor has handled the tap and placed its caret.
                            scope.launch {
                                withFrameNanos {}
                                state.selection = ThumbnailGeometry.selectionOf(offset)
                            }
                        },
                    )
                    .semantics { contentDescription = label },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = ink),
            cursorBrush = SolidColor(cursor),
            onTextLayout = { host.layout = it },
            minLines = minLines,
            keyboardOptions = keyboardOptions,
            interactionSource = interaction,
        )
    }
}

private fun Color.orElse(other: Color): Color = if (this == Color.Unspecified) other else this

@Composable
private fun LinkRow(
    value: String,
    onValueChange: (String) -> Unit,
    hadLink: Boolean,
    onApply: () -> Unit,
    onRemove: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(stringResource(R.string.markdown_editor_link_address)) },
            placeholder = { Text("https://") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onApply) {
                Text(stringResource(R.string.markdown_editor_link_apply))
            }
            if (hadLink) {
                TextButton(onClick = onRemove) {
                    Text(stringResource(R.string.markdown_editor_link_remove))
                }
            }
            TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
        }
    }
}

@Composable
private fun ToolbarButton(
    icon: ImageVector,
    description: Int,
    tint: Color,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconToggleButton(
        checked = active,
        onCheckedChange = { onClick() },
        enabled = enabled,
        modifier = Modifier.size(44.dp),
        colors =
            IconButtonDefaults.iconToggleButtonColors(
                contentColor = tint,
                checkedContainerColor = tint.copy(alpha = 0.16f),
                checkedContentColor = tint,
            ),
    ) {
        Icon(
            icon,
            contentDescription = stringResource(description),
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * The texts a field emitted that its caller may still hand back. The caller's value arrives
 * asynchronously, so a fast typist sees older strings echoed; those are not outside changes.
 */
internal class EmitEchoes(private val limit: Int = 64) {
    private val recent = ArrayDeque<String>()

    fun record(text: String) {
        recent.addLast(text)
        while (recent.size > limit) recent.removeFirst()
    }

    fun isEcho(value: String): Boolean = value in recent

    fun clear() = recent.clear()
}

/** How long the keyboard needs to stop moving before the selection is scrolled back into view. */
private const val KEYBOARD_SETTLE_MS = 150L
