package blue.anika.wolle.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.BuildConfig
import blue.anika.wolle.R
import blue.anika.wolle.data.settings.NavbarItemPreference
import blue.anika.wolle.data.settings.ThemeMode
import blue.anika.wolle.ui.navigation.NavbarCustomization
import blue.anika.wolle.ui.navigation.TopLevelDestination
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private const val GITHUB_URL = "https://github.com/pschmitt/stricknani"

/** SNA-18: account (sign-out), appearance (theme), sync status, and an About section. */
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val serverUrl by viewModel.serverUrl.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val lastSyncedMillis by viewModel.lastSyncedMillis.collectAsStateWithLifecycle()
    val serverMeta by viewModel.serverMeta.collectAsStateWithLifecycle()
    val navbarItems by viewModel.navbarItems.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    var showSignOutDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            SettingsSectionTitle("Account")
            SettingsRow(label = "Server", value = serverUrl.ifBlank { "Not connected" })
            Button(
                onClick = { showSignOutDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(stringResource(R.string.settings_sign_out))
            }
            Text(
                text = stringResource(R.string.settings_sign_out_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        item {
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            SettingsSectionTitle("Appearance")
            ThemeModeSelector(selected = themeMode, onSelect = viewModel::setThemeMode)
        }

        item {
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            SettingsSectionTitle("Navigation")
            Text(
                text = "Choose which destinations appear in the bottom bar, and in what order.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            )
            NavbarItemsEditor(
                items = navbarItems,
                onMove = viewModel::moveNavbarItem,
                onVisibilityChange = viewModel::setNavbarItemVisible,
            )
        }

        item {
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            SettingsSectionTitle("Sync")
            SettingsRow(label = "Last synced", value = formatLastSynced(lastSyncedMillis))
            SettingsRow(
                label = "Sync policy",
                value = "Automatically every 6 hours, on launch, and right after each edit",
            )
        }

        item {
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            SettingsSectionTitle("About")
            SettingsRow(label = "App version", value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            SettingsRow(label = "Build", value = BuildConfig.GIT_REVISION)
            SettingsRow(
                label = "Server version",
                value = serverMeta?.let { "${it.version} (${it.buildId})" } ?: "Unavailable",
            )
            SettingsRow(label = "License", value = "GPL-3.0")
            TextButton(
                onClick = { uriHandler.openUri(GITHUB_URL) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("View source on GitHub")
            }
        }
    }

    if (showSignOutDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutDialog = false },
            title = { Text("Sign out?") },
            text = { Text(stringResource(R.string.settings_sign_out_description)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSignOutDialog = false
                        viewModel.signOut()
                    }
                ) {
                    Text(stringResource(R.string.settings_sign_out))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ThemeModeSelector(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    Column(Modifier.selectableGroup().padding(horizontal = 8.dp)) {
        ThemeMode.entries.forEach { mode ->
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .selectable(
                            selected = mode == selected,
                            onClick = { onSelect(mode) },
                            role = Role.RadioButton,
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = mode == selected, onClick = null)
                Text(
                    text = mode.label(),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

private fun ThemeMode.label(): String =
    when (this) {
        ThemeMode.SYSTEM -> "Follow system"
        ThemeMode.LIGHT -> "Light"
        ThemeMode.DARK -> "Dark"
    }

@Composable
private fun NavbarItemsEditor(
    items: List<NavbarItemPreference>,
    onMove: (index: Int, delta: Int) -> Unit,
    onVisibilityChange: (id: String, visible: Boolean) -> Unit,
) {
    val destinations = NavbarCustomization.toDestinations(items)
    Column {
        items.forEachIndexed { index, item ->
            val destination = destinations.getOrNull(index) ?: return@forEachIndexed
            val isSettings = destination == TopLevelDestination.SETTINGS
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = item.visible,
                    onCheckedChange = { checked -> onVisibilityChange(item.id, checked) },
                    enabled = !isSettings,
                )
                Icon(
                    destination.icon,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(
                    text = destination.label,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onMove(index, -1) }, enabled = index > 0) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up")
                }
                IconButton(onClick = { onMove(index, 1) }, enabled = index < items.lastIndex) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down")
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun SettingsRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
        )
    }
}

private fun formatLastSynced(millis: Long?): String {
    if (millis == null) return "Never"
    val formatter =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withZone(ZoneId.systemDefault())
    return formatter.format(Instant.ofEpochMilli(millis))
}
