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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.R
import blue.anika.wolle.ui.common.MdiIcons
import blue.anika.wolle.ui.common.PhosphorIcons

/**
 * Create (the website's `/create`): New project and New yarn. Importing from a link starts in the
 * editor of a new project or yarn, which has a card for it at the top.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateScreen(
    onBack: () -> Unit,
    onNewProject: () -> Unit,
    onNewYarn: () -> Unit,
    offerViewModel: ImportOfferViewModel = hiltViewModel(),
) {
    val offers by offerViewModel.offers.collectAsStateWithLifecycle()
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
            items(createOptions(offers), key = { it.kind }) { option ->
                CreateTile(
                    option = option,
                    onClick = {
                        when (option.kind) {
                            CreateKind.NewProject -> onNewProject()
                            CreateKind.NewYarn -> onNewYarn()
                        }
                    },
                )
            }
        }
    }
}

private fun CreateKind.icon(): ImageVector =
    when (this) {
        CreateKind.NewProject -> PhosphorIcons.Hoodie
        CreateKind.NewYarn -> MdiIcons.YarnBall
    }

@Composable
private fun CreateTile(option: CreateOption, onClick: () -> Unit) {
    val title = stringResource(option.titleRes)
    val description = stringResource(option.descriptionRes)
    val yarnTone = option.kind == CreateKind.NewYarn
    val colors = MaterialTheme.colorScheme
    val wash = if (yarnTone) colors.secondaryContainer else colors.tertiaryContainer
    val tone = if (yarnTone) colors.onSecondaryContainer else colors.onTertiaryContainer
    Card(
        modifier =
            Modifier.fillMaxWidth()
                .testTag("create-${option.kind.name}")
                .clickable(role = Role.Button, onClick = onClick)
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = listOf(title, description).joinToString(". ")
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
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = tone)
        }
    }
}
