package blue.anika.wolle.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.R
import blue.anika.wolle.data.settings.DetailCardDomain
import blue.anika.wolle.data.settings.ProjectDetailCard
import blue.anika.wolle.data.settings.YarnDetailCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailCardOrderSettingsScreen(
    onBack: () -> Unit,
    viewModel: DetailCardOrderSettingsViewModel = hiltViewModel(),
) {
    val projectOrder by viewModel.projectOrder.collectAsStateWithLifecycle()
    val yarnOrder by viewModel.yarnOrder.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(SettingsCategory.DetailCards.title()) },
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
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                SettingsGroupCard(
                    title = stringResource(R.string.settings_detail_cards_project_title),
                    icon = SettingsCategory.DetailCards.icon,
                ) {
                    Text(
                        stringResource(R.string.settings_detail_cards_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    DetailCardOrderEditor(
                        domain = DetailCardDomain.PROJECT,
                        order = projectOrder,
                        onMove = viewModel::move,
                        onReset = viewModel::reset,
                    )
                }
            }
            item {
                SettingsGroupCard(
                    title = stringResource(R.string.settings_detail_cards_yarn_title),
                    icon = SettingsCategory.DetailCards.icon,
                ) {
                    Text(
                        stringResource(R.string.settings_detail_cards_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    DetailCardOrderEditor(
                        domain = DetailCardDomain.YARN,
                        order = yarnOrder,
                        onMove = viewModel::move,
                        onReset = viewModel::reset,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailCardOrderEditor(
    domain: DetailCardDomain,
    order: List<String>,
    onMove: (DetailCardDomain, Int, Int) -> Unit,
    onReset: (DetailCardDomain) -> Unit,
) {
    order.forEachIndexed { index, id ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = detailCardLabel(domain, id),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { onMove(domain, index, index - 1) },
                enabled = index > 0,
            ) {
                Icon(
                    Icons.Filled.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.common_move_up),
                )
            }
            IconButton(
                onClick = { onMove(domain, index, index + 1) },
                enabled = index < order.lastIndex,
            ) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.common_move_down),
                )
            }
        }
    }
    TextButton(
        onClick = { onReset(domain) },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text(stringResource(R.string.settings_detail_cards_reset))
    }
}

@Composable
private fun detailCardLabel(domain: DetailCardDomain, id: String): String =
    when (domain) {
        DetailCardDomain.PROJECT ->
            when (id) {
                ProjectDetailCard.ATTACHMENTS ->
                    stringResource(R.string.project_detail_attachments_title)
                ProjectDetailCard.DETAILS -> stringResource(R.string.project_detail_details_title)
                ProjectDetailCard.STITCH_SAMPLE ->
                    stringResource(R.string.common_field_stitch_sample)
                ProjectDetailCard.LINKED_YARNS ->
                    stringResource(R.string.project_detail_linked_yarns_title)
                ProjectDetailCard.DESCRIPTION ->
                    stringResource(R.string.project_detail_description_title)
                ProjectDetailCard.STEPS -> stringResource(R.string.project_detail_steps_title)
                ProjectDetailCard.NOTES -> stringResource(R.string.project_detail_notes_title)
                else -> id
            }
        DetailCardDomain.YARN ->
            when (id) {
                YarnDetailCard.DETAILS -> stringResource(R.string.yarn_detail_details_title)
                YarnDetailCard.DESCRIPTION ->
                    stringResource(R.string.project_detail_description_title)
                YarnDetailCard.USED_IN -> stringResource(R.string.yarn_detail_used_in_title)
                YarnDetailCard.NOTES -> stringResource(R.string.project_detail_notes_title)
                else -> id
            }
    }
