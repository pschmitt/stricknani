package blue.anika.wolle.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import blue.anika.wolle.R
import blue.anika.wolle.ui.theme.stricknaniMarkdownTypography
import com.mikepenz.markdown.m3.Markdown

/**
 * A Markdown text field: the plain text field with a formatting toolbar above it (bold, italic,
 * heading, lists, link; each inserts or wraps Markdown at the selection), and a Raw / Preview
 * toggle that shows the text as the reader renders it. The value stays the Markdown string, so
 * drafts, dirty tracking and saving behave exactly as with a plain text field.
 *
 * Native WYSIWYG editing is not offered: Compose has no maintained rich-text editor that
 * round-trips Markdown, so this is the closest dependable equivalent of the web editor.
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
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    LaunchedEffect(value) {
        if (value != field.text) field = TextFieldValue(value, TextRange(value.length))
    }
    var preview by rememberSaveable { mutableStateOf(false) }
    val tint = LocalContentColor.current

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val formattingDescription = stringResource(R.string.markdown_editor_formatting)
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()).semantics {
                    contentDescription = formattingDescription
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!preview) {
                    ToolbarButton(Icons.Filled.FormatBold, R.string.markdown_editor_bold, tint) {
                        apply(field, MarkdownFormat.BOLD, onValueChange) { field = it }
                    }
                    ToolbarButton(
                        Icons.Filled.FormatItalic,
                        R.string.markdown_editor_italic,
                        tint,
                    ) {
                        apply(field, MarkdownFormat.ITALIC, onValueChange) { field = it }
                    }
                    ToolbarButton(Icons.Filled.Title, R.string.markdown_editor_heading, tint) {
                        apply(field, MarkdownFormat.HEADING, onValueChange) { field = it }
                    }
                    ToolbarButton(
                        Icons.AutoMirrored.Filled.FormatListBulleted,
                        R.string.markdown_editor_bullet_list,
                        tint,
                    ) {
                        apply(field, MarkdownFormat.BULLET_LIST, onValueChange) { field = it }
                    }
                    ToolbarButton(
                        Icons.Filled.FormatListNumbered,
                        R.string.markdown_editor_numbered_list,
                        tint,
                    ) {
                        apply(field, MarkdownFormat.NUMBERED_LIST, onValueChange) { field = it }
                    }
                    ToolbarButton(Icons.Filled.Link, R.string.markdown_editor_link, tint) {
                        apply(field, MarkdownFormat.LINK, onValueChange) { field = it }
                    }
                }
            }
            val modeDescription = stringResource(R.string.markdown_editor_mode)
            SingleChoiceSegmentedButtonRow(
                Modifier.semantics { contentDescription = modeDescription }
            ) {
                SegmentedButton(
                    selected = !preview,
                    onClick = { preview = false },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) {
                    Text(stringResource(R.string.markdown_editor_raw), maxLines = 1)
                }
                SegmentedButton(
                    selected = preview,
                    onClick = { preview = true },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) {
                    Text(stringResource(R.string.markdown_editor_preview), maxLines = 1)
                }
            }
        }
        if (preview) {
            MarkdownPreviewBox(markdownPreviewSource(value, keepLineBreaks))
        } else {
            OutlinedTextField(
                value = field,
                onValueChange = {
                    field = it
                    if (it.text != value) onValueChange(it.text)
                },
                label = { FieldLabel(label, labelIcon) },
                modifier = fieldModifier.fillMaxWidth(),
                minLines = minLines,
                keyboardOptions = keyboardOptions,
                colors = colors,
            )
        }
    }
}

private fun apply(
    field: TextFieldValue,
    format: MarkdownFormat,
    onValueChange: (String) -> Unit,
    setField: (TextFieldValue) -> Unit,
) {
    val next = applyMarkdownFormat(field, format)
    setField(next)
    if (next.text != field.text) onValueChange(next.text)
}

@Composable
private fun ToolbarButton(
    icon: ImageVector,
    description: Int,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
        Icon(
            icon,
            contentDescription = stringResource(description),
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun MarkdownPreviewBox(source: String) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = androidx.compose.ui.graphics.Color.Transparent,
        border = BorderStroke(1.dp, LocalContentColor.current.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
    ) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            if (source.isBlank()) {
                Text(
                    stringResource(R.string.markdown_editor_preview_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalContentColor.current.copy(alpha = 0.6f),
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Markdown(
                    content = normalizeMarkdownContent(source),
                    typography = stricknaniMarkdownTypography(),
                )
            }
        }
    }
}
