package blue.anika.wolle.ui.create

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.R
import blue.anika.wolle.ui.common.MdiIcons
import blue.anika.wolle.ui.common.PhosphorIcons
import blue.anika.wolle.ui.projects.ProjectImportDialog
import blue.anika.wolle.ui.projects.ProjectsListViewModel
import blue.anika.wolle.ui.share.ShareImportKind
import blue.anika.wolle.ui.yarns.YarnImportDialog
import blue.anika.wolle.ui.yarns.YarnsListViewModel

/**
 * Create (the website's `/create`): New project, New yarn, Import a project and Import a yarn. The
 * import tiles open the same import dialogs as the Projects and Yarns lists, backed by the same
 * view models, and are greyed out with a reason when the server has link imports off.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateScreen(
    onBack: () -> Unit,
    onNewProject: () -> Unit,
    onNewYarn: () -> Unit,
    initialImportKind: ShareImportKind? = null,
    initialImportUrl: String? = null,
    projects: ProjectsListViewModel = hiltViewModel(),
    yarns: YarnsListViewModel = hiltViewModel(),
) {
    val availability by projects.importAvailability.collectAsStateWithLifecycle()
    val projectImportState by projects.importState.collectAsStateWithLifecycle()
    val yarnImportState by yarns.importState.collectAsStateWithLifecycle()
    val stashYarns by projects.stashYarns.collectAsStateWithLifecycle()
    // A shared link opens the matching import dialog with the link filled in. The user still has to
    // confirm there: nothing is imported from here.
    var showProjectImport by rememberSaveable {
        mutableStateOf(initialImportKind == ShareImportKind.Project)
    }
    var showYarnImport by rememberSaveable {
        mutableStateOf(initialImportKind == ShareImportKind.Yarn)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.more_create_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding).testTag("create-list"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.create_heading),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        stringResource(R.string.create_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(createOptions(availability), key = { it.kind }) { option ->
                CreateTile(
                    option = option,
                    onClick = {
                        when (option.kind) {
                            CreateKind.NewProject -> onNewProject()
                            CreateKind.NewYarn -> onNewYarn()
                            CreateKind.ImportProject -> showProjectImport = true
                            CreateKind.ImportYarn -> showYarnImport = true
                        }
                    },
                )
            }
        }
    }

    ProjectImportDialog(
        visible = showProjectImport,
        initialUrl =
            initialImportUrl.takeIf { initialImportKind == ShareImportKind.Project }.orEmpty(),
        state = projectImportState,
        availability = availability,
        onStart = projects::startImport,
        onConfirm = projects::confirmImport,
        thumbnailUrl = projects::importThumbnailUrl,
        stashYarns = stashYarns,
        stashYarnPreviewUrl = projects::stashYarnPreviewUrl,
        onCancel = {
            showProjectImport = false
            projects.cancelImport()
        },
        onRetry = projects::retryImport,
    )
    YarnImportDialog(
        visible = showYarnImport,
        initialUrl =
            initialImportUrl.takeIf { initialImportKind == ShareImportKind.Yarn }.orEmpty(),
        state = yarnImportState,
        availability = availability,
        onStart = yarns::startImport,
        onConfirm = yarns::confirmImport,
        onCancel = {
            showYarnImport = false
            yarns.cancelImport()
        },
        onRetry = yarns::retryImport,
        thumbnailUrl = yarns::importThumbnailUrl,
    )
}

private fun CreateKind.icon(): ImageVector =
    when (this) {
        CreateKind.NewProject -> PhosphorIcons.Hoodie
        CreateKind.NewYarn -> MdiIcons.YarnBall
        CreateKind.ImportProject,
        CreateKind.ImportYarn -> PhosphorIcons.DownloadSimple
    }

@Composable
private fun CreateTile(option: CreateOption, onClick: () -> Unit) {
    val title = stringResource(option.titleRes)
    val description = stringResource(option.descriptionRes)
    val reason = option.disabledReasonRes?.let { stringResource(it) }
    val note = option.noteRes?.let { stringResource(it) }
    val yarnTone = option.kind == CreateKind.NewYarn || option.kind == CreateKind.ImportYarn
    val colors = MaterialTheme.colorScheme
    val wash = if (yarnTone) colors.secondaryContainer else colors.tertiaryContainer
    val tone = if (yarnTone) colors.onSecondaryContainer else colors.onTertiaryContainer
    Card(
        modifier =
            Modifier.fillMaxWidth()
                .testTag("create-${option.kind.name}")
                .alpha(if (option.enabled) 1f else 0.6f)
                .clickable(enabled = option.enabled, role = Role.Button, onClick = onClick)
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription =
                        listOfNotNull(title, description, reason ?: note).joinToString(". ")
                    if (!option.enabled) disabled()
                },
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = wash),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier.size(52.dp).background(tone.copy(alpha = 0.16f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(option.kind.icon(), contentDescription = null, tint = tone)
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                (reason ?: note)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (option.enabled) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = tone)
            }
        }
    }
}
