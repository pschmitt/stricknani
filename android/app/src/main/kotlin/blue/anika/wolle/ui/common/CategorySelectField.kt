package blue.anika.wolle.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import blue.anika.wolle.R

/**
 * The names offered by the category dropdown: [categories] sorted by name (case-insensitive,
 * duplicates dropped), plus [current] when it is not among them (a legacy or just typed name).
 */
fun categoryOptions(categories: List<String>, current: String): List<String> {
    val seen = mutableSetOf<String>()
    val names = mutableListOf<String>()
    for (name in categories) {
        val key = name.trim().lowercase()
        if (key.isNotEmpty() && seen.add(key)) names += name
    }
    val trimmed = current.trim()
    if (trimmed.isNotEmpty() && seen.add(trimmed.lowercase())) names += trimmed
    return names.sortedWith(String.CASE_INSENSITIVE_ORDER)
}

/** The option that stands for [current]; the server matches names case-insensitively. */
fun selectedCategoryOption(options: List<String>, current: String): String {
    val trimmed = current.trim()
    if (trimmed.isEmpty()) return ""
    return options.firstOrNull { it.equals(trimmed, ignoreCase = true) } ?: trimmed
}

/**
 * A dropdown of the user's categories with "No category" and "New category…". The latter swaps in a
 * text field; Done or leaving the field confirms the name, Escape cancels. A new name is just the
 * name: the server creates the category when the project is saved, so this works offline too.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategorySelectField(
    value: String,
    onValueChange: (String) -> Unit,
    categories: List<String>,
    label: String,
    modifier: Modifier = Modifier,
    labelIcon: ImageVector? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    val options = remember(categories, value) { categoryOptions(categories, value) }
    val selected = selectedCategoryOption(options, value)

    if (adding) {
        val focusRequester = remember { FocusRequester() }
        var hadFocus by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
        fun confirm() {
            if (!adding) return
            adding = false
            val name = newName.trim()
            if (name.isNotEmpty()) onValueChange(name)
        }
        OutlinedTextField(
            value = newName,
            onValueChange = { newName = it },
            label = {
                FieldLabel(
                    stringResource(R.string.projects_list_add_category_dialog_title),
                    labelIcon,
                )
            },
            singleLine = true,
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                ),
            keyboardActions = KeyboardActions(onDone = { confirm() }),
            modifier =
                modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged {
                        if (it.isFocused) hadFocus = true else if (hadFocus) confirm()
                    }
                    .onPreviewKeyEvent {
                        if (it.key == Key.Escape && it.type == KeyEventType.KeyUp) {
                            adding = false
                            newName = ""
                            true
                        } else {
                            false
                        }
                    },
        )
        return
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = selected.ifEmpty { stringResource(R.string.editor_category_none) },
            onValueChange = {},
            readOnly = true,
            label = { FieldLabel(label, labelIcon) },
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier.fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.editor_category_none)) },
                onClick = {
                    onValueChange("")
                    expanded = false
                },
                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
            )
            options.forEach { name ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        onValueChange(name)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.editor_category_new)) },
                onClick = {
                    newName = ""
                    adding = true
                    expanded = false
                },
                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
            )
        }
    }
}
