package blue.anika.wolle.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import blue.anika.wolle.R
import blue.anika.wolle.data.db.entity.YarnEntity
import blue.anika.wolle.data.repository.ImportedYarn

/**
 * The yarn part of the import review. For each yarn the page names: use a stash yarn (the server's
 * best match is preselected and any other yarn can be picked), create a new one from the extracted
 * details, or leave it out. Below that, any further stash yarns can be linked too.
 */
@Composable
internal fun ProjectImportYarns(
    review: ProjectImportReview,
    onReview: (ProjectImportReview) -> Unit,
    stash: List<YarnEntity>,
    previewUrl: (YarnEntity) -> String?,
    modifier: Modifier = Modifier,
) {
    // Which picker is open: a yarn index, EXTRA_PICKER for the extra list, or none.
    var picker by remember { mutableStateOf<Int?>(null) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(R.string.import_yarns_title),
            style = MaterialTheme.typography.titleSmall,
        )
        if (review.yarns.isNotEmpty()) {
            Text(
                stringResource(R.string.import_yarns_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        review.yarns.forEachIndexed { index, entry ->
            ImportYarnCard(
                index = index,
                entry = entry,
                review = review,
                onReview = onReview,
                stash = stash,
                previewUrl = previewUrl,
                onPick = { picker = index },
            )
        }
        HorizontalDivider()
        Text(
            stringResource(R.string.import_yarn_extra_title),
            style = MaterialTheme.typography.titleSmall,
        )
        review.extraStashIds.forEach { id ->
            val yarn = stash.firstOrNull { it.id == id } ?: return@forEach
            Row(verticalAlignment = Alignment.CenterVertically) {
                StashYarnRow(yarn, previewUrl, Modifier.weight(1f))
                IconButton(
                    onClick = { onReview(review.removeExtra(id)) },
                    modifier = Modifier.testTag("import-yarn-extra-remove"),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.import_yarn_remove, yarn.name),
                    )
                }
            }
        }
        OutlinedButton(
            onClick = { picker = EXTRA_PICKER },
            modifier = Modifier.heightIn(min = 48.dp).testTag("import-yarn-extra-add"),
        ) {
            Text(stringResource(R.string.import_yarn_extra_add))
        }
    }
    when (val open = picker) {
        null -> Unit
        EXTRA_PICKER ->
            YarnPickerSheet(
                yarns = stash,
                selected = review.extraStashIds.toSet(),
                previewUrl = previewUrl,
                onToggle = { id ->
                    onReview(
                        if (id in review.extraStashIds) review.removeExtra(id)
                        else review.addExtra(id)
                    )
                },
                onDismiss = { picker = null },
            )
        else ->
            YarnPickerSheet(
                yarns = stash,
                selected = setOfNotNull(review.yarns.getOrNull(open)?.stashId),
                previewUrl = previewUrl,
                onToggle = { id ->
                    onReview(review.pickStash(open, id))
                    picker = null
                },
                onDismiss = { picker = null },
            )
    }
}

@Composable
private fun ImportYarnCard(
    index: Int,
    entry: ImportYarnReview,
    review: ProjectImportReview,
    onReview: (ProjectImportReview) -> Unit,
    stash: List<YarnEntity>,
    previewUrl: (YarnEntity) -> String?,
    onPick: () -> Unit,
) {
    val yarn = entry.yarn
    Column(
        Modifier.fillMaxWidth().testTag("import-yarn-$index"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(yarn.name, style = MaterialTheme.typography.titleSmall)
        yarn.amount?.let {
            Text(
                "${stringResource(R.string.yarn_detail_field_amount)}: $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(Modifier.selectableGroup()) {
            ImportYarnChoice.entries.forEach { choice ->
                Row(
                    Modifier.fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .selectable(
                            selected = entry.choice == choice,
                            role = Role.RadioButton,
                            onClick = { onReview(review.choose(index, choice)) },
                        )
                        .testTag("import-yarn-$index-${choice.name.lowercase()}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = entry.choice == choice, onClick = null)
                    Text(
                        stringResource(choiceLabel(choice)),
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
        }
        when (entry.choice) {
            ImportYarnChoice.Stash -> {
                val picked = stash.firstOrNull { it.id == entry.stashId }
                if (picked != null) {
                    StashYarnRow(picked, previewUrl, Modifier.fillMaxWidth())
                    if (entry.stashId == yarn.suggestedStashId) {
                        Text(
                            stringResource(R.string.import_yarn_suggested),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                } else {
                    Text(
                        stringResource(R.string.import_yarn_pick_prompt),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedButton(
                    onClick = onPick,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("import-yarn-$index-pick"),
                ) {
                    Text(
                        stringResource(
                            if (picked == null) R.string.import_yarn_pick
                            else R.string.import_yarn_pick_other
                        )
                    )
                }
            }
            ImportYarnChoice.New -> {
                Text(
                    stringResource(R.string.import_yarn_new_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                NewYarnFields(yarn) { edited -> onReview(review.editYarn(index) { edited }) }
            }
            ImportYarnChoice.Skip ->
                Text(
                    stringResource(R.string.import_yarn_skip_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
    }
}

@Composable
private fun NewYarnFields(yarn: ImportedYarn, onChange: (ImportedYarn) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = yarn.name,
            onValueChange = { onChange(yarn.copy(name = it)) },
            label = { Text(stringResource(R.string.import_yarn_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = yarn.brand.orEmpty(),
            onValueChange = { onChange(yarn.copy(brand = it.ifBlank { null })) },
            label = { Text(stringResource(R.string.yarn_detail_field_brand)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = yarn.colorway.orEmpty(),
            onValueChange = { onChange(yarn.copy(colorway = it.ifBlank { null })) },
            label = { Text(stringResource(R.string.yarn_detail_field_colorway)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        yarnFacts(yarn)?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "100% Wool · 50 g · 105 m": what the product page told us, shown next to the editable names. */
internal fun yarnFacts(yarn: ImportedYarn): String? =
    listOfNotNull(
            yarn.fiberContent,
            yarn.weightCategory,
            yarn.weightGrams?.let { "$it g" },
            yarn.lengthMeters?.let { "$it m" },
        )
        .joinToString(" · ")
        .ifBlank { null }

@Composable
private fun StashYarnRow(
    yarn: YarnEntity,
    previewUrl: (YarnEntity) -> String?,
    modifier: Modifier,
) {
    Row(
        modifier.heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        YarnThumbnail(previewUrl(yarn))
        Column(Modifier.weight(1f)) {
            Text(yarn.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            yarnSubtitle(yarn)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

private fun choiceLabel(choice: ImportYarnChoice): Int =
    when (choice) {
        ImportYarnChoice.Stash -> R.string.import_yarn_choice_stash
        ImportYarnChoice.New -> R.string.import_yarn_choice_new
        ImportYarnChoice.Skip -> R.string.import_yarn_choice_skip
    }

private const val EXTRA_PICKER = -1
