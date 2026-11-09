package blue.anika.wolle.ui.projects

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import blue.anika.wolle.R
import blue.anika.wolle.ui.common.FieldLabel
import blue.anika.wolle.ui.common.PhosphorIcons

/**
 * The project tags as an inline chip input, the same one the web editor has: an outlined box that
 * holds the tags as small chips followed by the text cursor, and wraps onto more lines as it fills.
 * A space or comma, the keyboard action, a paste or leaving the field turns the typed text into
 * chips ([onTextChange] with `commitAll` for the last two); Backspace in the empty field removes
 * the last chip.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TagChipField(
    tags: List<String>,
    text: String,
    suggestions: List<TagCount>,
    onPickSuggestion: (tag: String) -> Unit,
    onTextChange: (value: String, commitAll: Boolean) -> Unit,
    onRemove: (index: Int) -> Unit,
    onBackspaceInEmptyField: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val label = stringResource(R.string.project_editor_tags_label)
    val outline =
        if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.padding(horizontal = 4.dp)) {
            CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                ProvideTextStyle(MaterialTheme.typography.bodySmall) {
                    FieldLabel(label, PhosphorIcons.Tag)
                }
            }
        }
        FlowRow(
            modifier =
                Modifier.fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .border(
                        width = if (focused) 2.dp else 1.dp,
                        color = outline,
                        shape = OutlinedTextFieldDefaults.shape,
                    )
                    .clip(OutlinedTextFieldDefaults.shape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        focusRequester.requestFocus()
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            tags.forEachIndexed { index, tag ->
                val removeLabel = stringResource(R.string.project_editor_tag_remove, tag)
                key(index, tag) {
                    InputChip(
                        selected = false,
                        onClick = { focusRequester.requestFocus() },
                        label = { Text(tag) },
                        trailingIcon = {
                            Icon(
                                PhosphorIcons.X,
                                contentDescription = removeLabel,
                                modifier =
                                    Modifier.size(24.dp)
                                        .clip(CircleShape)
                                        .clickable(role = Role.Button) { onRemove(index) }
                                        .padding(4.dp),
                            )
                        },
                    )
                }
            }
            BasicTextField(
                value = text,
                onValueChange = { value ->
                    // A paste adds several characters at once; typing adds one at a time.
                    val pasted = value.length - text.length > 1 && value.any(::isTagSeparator)
                    onTextChange(value, pasted)
                },
                singleLine = true,
                textStyle =
                    MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions =
                    KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done,
                    ),
                keyboardActions = KeyboardActions(onDone = { onTextChange(text, true) }),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (tags.isEmpty() && text.isEmpty()) {
                            Text(
                                stringResource(R.string.project_editor_tags_placeholder),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    }
                },
                modifier =
                    Modifier.widthIn(min = 96.dp)
                        .heightIn(min = 40.dp)
                        .focusRequester(focusRequester)
                        .onFocusChanged { state ->
                            if (focused && !state.isFocused && text.isNotEmpty()) {
                                onTextChange(text, true)
                            }
                            focused = state.isFocused
                        }
                        .onPreviewKeyEvent { event ->
                            val backspace =
                                event.type == KeyEventType.KeyDown && event.key == Key.Backspace
                            if (backspace && text.isEmpty() && tags.isNotEmpty()) {
                                onBackspaceInEmptyField()
                                true
                            } else {
                                false
                            }
                        }
                        .semantics { contentDescription = label },
            )
        }
        if (focused && suggestions.isNotEmpty()) {
            val suggestionsLabel = stringResource(R.string.project_editor_tags_suggestions)
            Row(
                modifier =
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).semantics {
                        contentDescription = suggestionsLabel
                    },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                suggestions.forEach { suggestion ->
                    key(suggestion.tag) {
                        SuggestionChip(
                            onClick = { onPickSuggestion(suggestion.tag) },
                            label = {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(suggestion.tag)
                                    Text(
                                        suggestion.count.toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.clearAndSetSemantics {},
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun isTagSeparator(char: Char): Boolean = char == ',' || char.isWhitespace()
