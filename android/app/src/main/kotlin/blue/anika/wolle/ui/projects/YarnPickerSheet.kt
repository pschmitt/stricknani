package blue.anika.wolle.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import blue.anika.wolle.R
import blue.anika.wolle.data.db.entity.YarnEntity
import blue.anika.wolle.ui.common.MdiIcons
import coil3.compose.AsyncImage

/** "Brand, colorway" - the second line that tells two yarns with the same name apart. */
internal fun yarnSubtitle(yarn: YarnEntity): String? =
    listOfNotNull(yarn.brand, yarn.colorway)
        .filter { it.isNotBlank() }
        .joinToString(", ")
        .ifBlank {
            null
        }

/** Yarns whose name, brand or colorway contain [query]; chosen ones first, then by name. */
internal fun filterYarnsForPicker(
    yarns: List<YarnEntity>,
    query: String,
    selected: Set<Int>,
): List<YarnEntity> {
    val needle = query.trim()
    return yarns
        .filter { yarn ->
            needle.isEmpty() ||
                listOfNotNull(yarn.name, yarn.brand, yarn.colorway).any {
                    it.contains(needle, ignoreCase = true)
                }
        }
        .sortedWith(compareBy<YarnEntity>({ it.id !in selected }, { it.name.lowercase() }))
}

@Composable
internal fun YarnThumbnail(url: String?, size: Int = 44) {
    if (url != null) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size.dp).clip(MaterialTheme.shapes.extraSmall),
        )
    } else {
        Surface(
            shape = MaterialTheme.shapes.extraSmall,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(size.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(MdiIcons.Sheep, contentDescription = null, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/**
 * Searchable multi-select for linking yarns to a project. Replaces a chip per yarn in the stash,
 * which turned into a wall of chips with a bigger stash.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun YarnPickerSheet(
    yarns: List<YarnEntity>,
    selected: Set<Int>,
    previewUrl: (YarnEntity) -> String?,
    onToggle: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by rememberSaveable { mutableStateOf("") }
    // Keep the order stable while ticking boxes; re-sort only when the search changes.
    val initialSelection = remember { selected }
    val shown = remember(yarns, query) { filterYarnsForPicker(yarns, query, initialSelection) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text(
                stringResource(R.string.project_editor_pick_yarns_title),
                style = MaterialTheme.typography.titleLarge,
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.project_editor_pick_yarns_search)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge,
                modifier =
                    Modifier.fillMaxWidth().padding(top = 12.dp).testTag("yarn-picker-search"),
            )
        }
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp).padding(top = 8.dp)) {
            items(shown, key = { it.id }) { yarn ->
                val checked = yarn.id in selected
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier =
                        Modifier.fillMaxWidth()
                            .toggleable(
                                value = checked,
                                role = Role.Checkbox,
                                onValueChange = { onToggle(yarn.id) },
                            )
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                ) {
                    YarnThumbnail(previewUrl(yarn))
                    Column(Modifier.weight(1f)) {
                        Text(
                            yarn.name,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        yarnSubtitle(yarn)?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Checkbox(checked = checked, onCheckedChange = null)
                }
            }
            if (shown.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.project_editor_pick_yarns_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(20.dp),
                    )
                }
            }
        }
        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Text(
                if (selected.isEmpty()) stringResource(R.string.common_done)
                else
                    pluralStringResource(
                        R.plurals.project_editor_pick_yarns_done,
                        selected.size,
                        selected.size,
                    )
            )
        }
    }
}
