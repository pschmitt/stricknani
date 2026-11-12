package blue.anika.wolle.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import blue.anika.wolle.R
import blue.anika.wolle.ui.common.PhosphorIcons

/**
 * Settings -> Admin: the administrator tools (accounts, audit log, backups). Only reachable when
 * the server says admin.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AdminSettingsScreen(
    onBack: () -> Unit,
    onOpenActivity: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenBackups: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(SettingsCategory.Admin.title()) },
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
            modifier = Modifier.fillMaxSize().padding(innerPadding).testTag("admin-settings"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                SettingsSingleItemCard {
                    AdminRow(
                        icon = PhosphorIcons.UsersThree,
                        title = stringResource(R.string.admin_accounts_title),
                        subtitle = stringResource(R.string.admin_accounts_subtitle),
                        tag = "admin-open-accounts",
                        onClick = onOpenAccounts,
                    )
                    AdminRow(
                        icon = PhosphorIcons.ClockCounterClockwise,
                        title = stringResource(R.string.admin_activity_title),
                        subtitle = stringResource(R.string.admin_activity_subtitle),
                        tag = "admin-open-activity",
                        onClick = onOpenActivity,
                    )
                    AdminRow(
                        icon = PhosphorIcons.FloppyDisk,
                        title = stringResource(R.string.backups_title),
                        subtitle = stringResource(R.string.admin_backups_subtitle),
                        tag = "admin-open-backups",
                        onClick = onOpenBackups,
                    )
                }
            }
        }
    }
}

@Composable
private fun AdminRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    tag: String,
    onClick: () -> Unit,
) {
    SettingsListItem(
        modifier =
            Modifier.testTag(tag).clickable(role = Role.Button, onClick = onClick).semantics {
                contentDescription = "$title: $subtitle"
                role = Role.Button
            },
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        },
    )
}
