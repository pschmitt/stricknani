package blue.anika.wolle.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import blue.anika.wolle.R
import coil3.compose.AsyncImage

/*
 * Building blocks shared by the project and yarn editors, so both behave the same:
 *  - the primary action (Save) lives at the bottom, in thumb reach, and rides above the keyboard;
 *  - photos are a strip of thumbnails you can see, not a list of file names;
 *  - free-text fields with common values offer suggestions instead of a wall of chips;
 *  - leaving with unsaved changes asks first.
 */

/** One thumbnail in an [EditorPhotoStrip]: a server URL or a local file path for [model]. */
data class EditorPhoto(
    val key: String,
    val model: Any?,
    val description: String,
    val onRemove: () -> Unit,
)

@Composable
fun EditorPhotoStrip(
    photos: List<EditorPhoto>,
    addLabel: String,
    removeLabel: String,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
    thumbnailSize: Int = 104,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 2.dp),
    ) {
        items(photos, key = { it.key }) { photo ->
            Box(Modifier.size(thumbnailSize.dp)) {
                AsyncImage(
                    model = photo.model,
                    contentDescription = photo.description.ifBlank { null },
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier.size(thumbnailSize.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .padding(0.dp),
                )
                // 48dp touch target, small visible badge.
                IconButton(
                    onClick = photo.onRemove,
                    modifier = Modifier.align(Alignment.TopEnd).size(44.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(26.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = removeLabel,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
        item(key = "add") {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier.size(thumbnailSize.dp)
                        .stitchedBorder(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                            inset = 3.dp,
                            cornerRadius = 19.dp,
                        )
                        .clip(MaterialTheme.shapes.medium)
                        .clickable(role = Role.Button, onClick = onAdd),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(8.dp),
                ) {
                    Icon(Icons.Filled.AddAPhoto, contentDescription = null)
                    Text(
                        addLabel,
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

/**
 * A text field that suggests common or existing values as you type (a combo box). Typing anything
 * else is fine - it is still free text, like on the website.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuggestionTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    suggestions: List<String>,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val filtered =
        remember(value, suggestions) {
            val query = value.trim()
            suggestions
                .distinct()
                .filter { it.isNotBlank() && !it.equals(query, ignoreCase = true) }
                .filter { query.isEmpty() || it.contains(query, ignoreCase = true) }
                .take(8)
        }
    ExposedDropdownMenuBox(
        expanded = expanded && filtered.isNotEmpty(),
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                onValueChange(it)
                expanded = true
            },
            label = { Text(label) },
            singleLine = true,
            supportingText = supportingText?.let { { Text(it) } },
            trailingIcon = {
                if (suggestions.isNotEmpty()) {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                }
            },
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Next,
                ),
            modifier =
                Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded && filtered.isNotEmpty(),
            onDismissRequest = { expanded = false },
        ) {
            filtered.forEach { suggestion ->
                DropdownMenuItem(
                    text = { Text(suggestion) },
                    onClick = {
                        onValueChange(suggestion)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

/**
 * The editor's Save button, pinned to the bottom and lifted above the keyboard, so saving never
 * needs a reach to the top corner or closing the keyboard first.
 */
@Composable
fun EditorSaveBar(isSaving: Boolean, onSave: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier =
                Modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Button(
                onClick = onSave,
                enabled = !isSaving,
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                if (isSaving) {
                    YarnLoader(size = 26.dp, modifier = Modifier.padding(end = 8.dp))
                }
                Text(
                    stringResource(R.string.common_save),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

/** "Discard your changes?" - shown when leaving an editor with unsaved edits. */
@Composable
fun DiscardChangesDialog(onKeepEditing: () -> Unit, onDiscard: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeepEditing,
        title = { Text(stringResource(R.string.editor_discard_title)) },
        text = { Text(stringResource(R.string.editor_discard_text)) },
        confirmButton = {
            TextButton(onClick = onKeepEditing) {
                Text(stringResource(R.string.editor_discard_keep))
            }
        },
        dismissButton = {
            TextButton(onClick = onDiscard) {
                Text(
                    stringResource(R.string.editor_discard_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
    )
}

/** A thin outlined row used for attachments and linked yarns. */
@Composable
fun EditorListRow(
    leading: @Composable () -> Unit,
    title: String,
    subtitle: String?,
    removeLabel: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 6.dp),
        ) {
            leading()
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Close, contentDescription = removeLabel)
            }
        }
    }
}
