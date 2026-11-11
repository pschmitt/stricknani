package blue.anika.wolle.ui.trash

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.R
import blue.anika.wolle.data.api.v2.ProjectV2Dto
import blue.anika.wolle.data.api.v2.YarnV2Dto
import blue.anika.wolle.data.repository.AdminFailure
import blue.anika.wolle.data.repository.TrashFailure
import blue.anika.wolle.ui.admin.AdminFailureState
import blue.anika.wolle.ui.common.DestructiveDeleteDialog
import blue.anika.wolle.ui.common.PhosphorIcons
import blue.anika.wolle.ui.common.YarnLoader
import coil3.compose.AsyncImage

/**
 * A project or yarn in the trash: read-only, online-only, with the banner and its Restore and
 * Delete permanently buttons (the website's `TrashedBanner`). The normal detail screens stay
 * Room-backed; this page shows the main fields of what the server returns for the owner or an
 * administrator.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashedItemScreen(
    onBack: () -> Unit,
    onRestored: (kind: String, id: Int) -> Unit,
    onPurged: () -> Unit,
    viewModel: TrashedItemViewModel = hiltViewModel(),
) {
    val phase by viewModel.phase.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val result by viewModel.result.collectAsStateWithLifecycle()
    var confirmPurge by remember { mutableStateOf(false) }

    LaunchedEffect(result) {
        when (result) {
            TrashedItemResult.Restored -> onRestored(viewModel.kind, viewModel.id)
            TrashedItemResult.Purged -> onPurged()
            null -> Unit
        }
        if (result != null) viewModel.consumeResult()
    }

    val name =
        when (val p = phase) {
            is TrashedItemPhase.Project -> p.project.name
            is TrashedItemPhase.Yarn -> p.yarn.name
            else -> null
        }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        name
                            ?: stringResource(
                                if (viewModel.kind == "yarn") R.string.trash_kind_yarn
                                else R.string.trash_kind_project
                            )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_nav_back),
                        )
                    }
                },
            )
        }
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding).testTag("trashed-item-screen")) {
            when (val p = phase) {
                TrashedItemPhase.Loading ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        YarnLoader()
                    }
                is TrashedItemPhase.Failed ->
                    AdminFailureState(
                        failure =
                            when (p.failure) {
                                TrashFailure.Offline -> AdminFailure.Offline
                                TrashFailure.NotAllowed -> AdminFailure.NotAllowed
                                TrashFailure.NotFound -> AdminFailure.NotFound
                                else -> AdminFailure.Other
                            },
                        onRetry = viewModel::load,
                        onBack = onBack,
                    )
                is TrashedItemPhase.Project ->
                    TrashedContent(
                        isYarn = false,
                        purgeAt = p.project.purgeAt,
                        busy = busy,
                        onRestore = viewModel::restore,
                        onPurge = { confirmPurge = true },
                    ) {
                        ProjectFields(p.project, viewModel::mediaUrl)
                    }
                is TrashedItemPhase.Yarn ->
                    TrashedContent(
                        isYarn = true,
                        purgeAt = p.yarn.purgeAt,
                        busy = busy,
                        onRestore = viewModel::restore,
                        onPurge = { confirmPurge = true },
                    ) {
                        YarnFields(p.yarn, viewModel::mediaUrl)
                    }
            }
        }
    }

    if (confirmPurge) {
        DestructiveDeleteDialog(
            title = stringResource(R.string.trash_purge_dialog_title),
            text = stringResource(R.string.trash_purge_dialog_text, name.orEmpty()),
            onConfirm = {
                confirmPurge = false
                viewModel.purge()
            },
            onDismiss = { confirmPurge = false },
        )
    }
}

@Composable
private fun TrashedContent(
    isYarn: Boolean,
    purgeAt: String?,
    busy: Boolean,
    onRestore: () -> Unit,
    onPurge: () -> Unit,
    fields: @Composable () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TrashedBanner(
                isYarn = isYarn,
                purgeAt = purgeAt,
                busy = busy,
                onRestore = onRestore,
                onPurge = onPurge,
            )
        }
        item { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { fields() } }
    }
}

/** "This project is in the trash. It will be deleted permanently in 5 days." with the actions. */
@Composable
private fun TrashedBanner(
    isYarn: Boolean,
    purgeAt: String?,
    busy: Boolean,
    onRestore: () -> Unit,
    onPurge: () -> Unit,
) {
    val days = daysUntilPurge(purgeAt, System.currentTimeMillis())
    val base =
        stringResource(if (isYarn) R.string.trash_banner_yarn else R.string.trash_banner_project)
    val message =
        when {
            days == null -> base
            days <= 0 -> "$base ${stringResource(R.string.trash_banner_due)}"
            else -> "$base ${pluralStringResource(R.plurals.trash_banner_time, days, days)}"
        }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth().testTag("trashed-banner"),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    PhosphorIcons.Trash,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
                Text(message, style = MaterialTheme.typography.bodyLarge)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onRestore,
                    enabled = !busy,
                    modifier = Modifier.testTag("trashed-restore"),
                ) {
                    Text(stringResource(R.string.trash_restore))
                }
                OutlinedButton(
                    onClick = onPurge,
                    enabled = !busy,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                    colors =
                        ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                    modifier = Modifier.testTag("trashed-purge"),
                ) {
                    Text(stringResource(R.string.trash_banner_delete))
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Pictures(urls: List<String?>) {
    val shown = urls.filterNotNull()
    if (shown.isEmpty()) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(shown) { url ->
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(120.dp).clip(MaterialTheme.shapes.medium),
            )
        }
    }
}

@Composable
private fun ProjectFields(project: ProjectV2Dto, mediaUrl: (String?) -> String?) {
    Pictures(project.images.map { mediaUrl(it.thumbnailUrl) })
    Field(stringResource(R.string.common_field_category), project.category)
    Field(stringResource(R.string.project_editor_tags_label), project.tags.joinToString(", "))
    Field(stringResource(R.string.common_field_needles), project.needles)
    Field(stringResource(R.string.common_field_stitch_sample), project.stitchSample)
    Field(stringResource(R.string.project_detail_description_title), project.description)
    Field(stringResource(R.string.project_detail_materials_title), project.otherMaterials)
    Field(stringResource(R.string.project_detail_notes_title), project.notes)
    if (project.steps.isNotEmpty()) {
        Text(
            stringResource(R.string.project_detail_steps_title),
            style = MaterialTheme.typography.titleMedium,
        )
        project.steps
            .sortedBy { it.stepNumber }
            .forEach { step ->
                Field("${step.stepNumber}. ${step.title}", step.description)
                if (step.description.isNullOrBlank()) {
                    Text(
                        "${step.stepNumber}. ${step.title}",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
    }
}

@Composable
private fun YarnFields(yarn: YarnV2Dto, mediaUrl: (String?) -> String?) {
    Pictures(yarn.photos.map { mediaUrl(it.thumbnailUrl) })
    Field(stringResource(R.string.yarn_detail_field_brand), yarn.brand)
    Field(stringResource(R.string.yarn_detail_field_colorway), yarn.colorway)
    Field(stringResource(R.string.yarn_detail_field_dye_lot), yarn.dyeLot)
    Field(stringResource(R.string.yarn_detail_field_fiber_content), yarn.fiberContent)
    Field(stringResource(R.string.yarn_detail_field_weight_category), yarn.weightCategory)
    Field(stringResource(R.string.yarn_detail_field_recommended_needles), yarn.recommendedNeedles)
    Field(
        stringResource(R.string.yarn_detail_field_skein_weight),
        yarn.weightGrams?.let { "$it g" },
    )
    Field(
        stringResource(R.string.yarn_detail_field_skein_length),
        yarn.lengthMeters?.let { "$it m" },
    )
    Field(stringResource(R.string.project_detail_description_title), yarn.description)
    Field(stringResource(R.string.project_detail_notes_title), yarn.notes)
}
