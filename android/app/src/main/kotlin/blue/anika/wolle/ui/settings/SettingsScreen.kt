package blue.anika.wolle.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.R
import blue.anika.wolle.data.repository.AccountProfile
import blue.anika.wolle.ui.admin.AdminAccessViewModel
import blue.anika.wolle.ui.common.AccountAvatar
import blue.anika.wolle.ui.common.MdiIcons
import blue.anika.wolle.ui.common.PhosphorIcons

/**
 * More hub (SNA-21; the Settings hub, now the "More" tab): a card of category rows, each navigating
 * to its own dedicated screen (`SettingsCategoryScreen`) - matches syncwich's Settings UX rather
 * than SNA-18's original single flat scrolling list. The SNA-21 redesign dropped the page header
 * along with the old flat list - every other top-level destination (Home's `TopAppBar`,
 * Projects'/Yarns' search field) has some visual anchor confirming which screen you're on; this one
 * didn't (user-reported, 2026-08-18).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onCategoryClick: (SettingsCategory) -> Unit,
    onNewProject: () -> Unit,
    onNewYarn: () -> Unit,
    onGaugeClick: () -> Unit,
    adminAccess: AdminAccessViewModel = hiltViewModel(),
    accountAvatar: AccountAvatarViewModel = hiltViewModel(),
) {
    val profile by accountAvatar.profile.collectAsStateWithLifecycle()
    val isAdmin by adminAccess.isAdmin.collectAsStateWithLifecycle()
    // Same order as the website's More page: Create, Gauge calculator, then the settings rows with
    // Admin and About near the end and the Account entry (avatar, settings, sign-out) last.
    val categories =
        (SettingsCategory.entries.filter { it != SettingsCategory.Account } +
                SettingsCategory.Account)
            .filter { it != SettingsCategory.Admin || isAdmin }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.more_title)) }) }) {
        innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding).testTag("settings-list"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                SettingsSingleItemCard {
                    MoreActionRow(
                        icon = PhosphorIcons.Hoodie,
                        title = stringResource(R.string.project_new_label),
                        subtitle = stringResource(R.string.more_create_project_subtitle),
                        tag = "more-new-project",
                        onClick = onNewProject,
                    )
                    MoreActionRow(
                        icon = MdiIcons.YarnBall,
                        title = stringResource(R.string.yarns_list_new_yarn_fab),
                        subtitle = stringResource(R.string.more_create_yarn_subtitle),
                        tag = "more-new-yarn",
                        onClick = onNewYarn,
                    )
                    MoreActionRow(
                        icon = PhosphorIcons.Ruler,
                        title = stringResource(R.string.gauge_calculator_title),
                        subtitle = stringResource(R.string.more_gauge_subtitle),
                        tag = "more-gauge",
                        onClick = onGaugeClick,
                    )
                }
            }
            item {
                SettingsSingleItemCard {
                    categories.forEach { category ->
                        SettingsCategoryRow(category, profile, onCategoryClick)
                    }
                }
            }
        }
    }
}

@Composable
private fun MoreActionRow(
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

@Composable
private fun SettingsCategoryRow(
    category: SettingsCategory,
    profile: AccountProfile,
    onClick: (SettingsCategory) -> Unit,
) {
    val title = category.title()
    val subtitle = category.subtitle()
    SettingsListItem(
        modifier =
            Modifier.clickable(role = Role.Button) { onClick(category) }
                .semantics {
                    contentDescription = "$title: $subtitle"
                    role = Role.Button
                },
        leadingContent = {
            if (category == SettingsCategory.Account) {
                AccountAvatar(profile.avatarUrl, profile.email, size = 24.dp)
            } else {
                Icon(category.icon, contentDescription = null)
            }
        },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        },
    )
}
