package blue.anika.wolle.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.R
import blue.anika.wolle.ui.common.PhosphorIcons

/**
 * User interface: how the app is laid out. The bottom bar and the detail card order open their own
 * screens; project timers is a switch right here. Every row has an icon, like the rest of More.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InterfaceSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel,
    onOpenCategory: (SettingsCategory) -> Unit,
) {
    val projectTimersEnabled by viewModel.projectTimersEnabled.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(SettingsCategory.Interface.title()) },
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
            modifier = Modifier.fillMaxSize().padding(innerPadding).testTag("interface-settings"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                SettingsSingleItemCard {
                    InterfacePageRow(SettingsCategory.Navigation, onOpenCategory)
                    ProjectTimersRow(projectTimersEnabled, viewModel::setProjectTimersEnabled)
                    InterfacePageRow(SettingsCategory.DetailCards, onOpenCategory)
                }
            }
        }
    }
}

@Composable
private fun InterfacePageRow(category: SettingsCategory, onOpen: (SettingsCategory) -> Unit) {
    val title = category.title()
    val subtitle = category.subtitle()
    SettingsListItem(
        modifier =
            Modifier.clickable(role = Role.Button) { onOpen(category) }
                .semantics {
                    contentDescription = "$title: $subtitle"
                    role = Role.Button
                },
        leadingContent = { Icon(category.icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        },
    )
}

@Composable
private fun ProjectTimersRow(checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingsListItem(
        modifier =
            Modifier.fillMaxWidth()
                .testTag("interface-project-timers")
                .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        leadingContent = { Icon(PhosphorIcons.Timer, contentDescription = null) },
        headlineContent = { Text(stringResource(R.string.settings_project_timers_title)) },
        supportingContent = { Text(stringResource(R.string.settings_project_timers_help)) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    )
}
