package blue.anika.wolle.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.R
import blue.anika.wolle.data.repository.AccountProfile
import blue.anika.wolle.ui.admin.AdminAccessViewModel
import blue.anika.wolle.ui.common.AccountAvatar
import blue.anika.wolle.ui.common.PhosphorIcons

/**
 * More hub (SNA-21; the Settings hub, now the "More" tab), in the website's More order: Create,
 * Categories, Gauge calculator, About, the Account row, the Android-only Settings group and Sign
 * out last. Each row opens its own dedicated screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onCategoryClick: (SettingsCategory) -> Unit,
    onCreateClick: () -> Unit,
    onGaugeClick: () -> Unit,
    adminAccess: AdminAccessViewModel = hiltViewModel(),
    accountAvatar: AccountAvatarViewModel = hiltViewModel(),
    settings: SettingsViewModel = hiltViewModel(),
) {
    val profile by accountAvatar.profile.collectAsStateWithLifecycle()
    val isAdmin by adminAccess.isAdmin.collectAsStateWithLifecycle()
    var showSignOutDialog by rememberSaveable { mutableStateOf(false) }
    val layout = moreLayout(isAdmin)
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.more_title)) }) }) {
        innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding).testTag("settings-list"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                SettingsSingleItemCard {
                    layout.links.forEach { link ->
                        when (link) {
                            MoreLink.Create ->
                                MoreActionRow(
                                    icon = PhosphorIcons.Sparkle,
                                    title = stringResource(R.string.more_create_title),
                                    subtitle = stringResource(R.string.more_create_subtitle),
                                    tag = "more-create",
                                    onClick = onCreateClick,
                                )
                            MoreLink.Gauge ->
                                MoreActionRow(
                                    icon = PhosphorIcons.Ruler,
                                    title = stringResource(R.string.gauge_calculator_title),
                                    subtitle = stringResource(R.string.more_gauge_subtitle),
                                    tag = "more-gauge",
                                    onClick = onGaugeClick,
                                )
                            is MoreLink.Category ->
                                SettingsCategoryRow(link.category, profile, onCategoryClick)
                        }
                    }
                }
            }
            item {
                SettingsSingleItemCard {
                    MoreAccountRow(profile) { onCategoryClick(SettingsCategory.Account) }
                }
            }
            item {
                Text(
                    stringResource(R.string.more_settings_header),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp).semantics { heading() },
                )
            }
            item {
                SettingsSingleItemCard {
                    layout.settings.forEach { category ->
                        SettingsCategoryRow(category, profile, onCategoryClick)
                    }
                }
            }
            item {
                SettingsSingleItemCard {
                    MoreActionRow(
                        icon = Icons.AutoMirrored.Filled.Logout,
                        title = stringResource(R.string.settings_sign_out),
                        subtitle = null,
                        tag = "more-sign-out",
                        onClick = { showSignOutDialog = true },
                        showArrow = false,
                    )
                }
            }
        }
    }

    if (showSignOutDialog) {
        SignOutDialog(
            onConfirm = {
                showSignOutDialog = false
                settings.signOut()
            },
            onDismiss = { showSignOutDialog = false },
        )
    }
}

@Composable
private fun MoreAccountRow(profile: AccountProfile, onClick: () -> Unit) {
    val title = stringResource(R.string.more_account_title)
    val subtitle = profile.email ?: stringResource(R.string.settings_category_account_subtitle)
    SettingsListItem(
        modifier =
            Modifier.testTag("more-account")
                .clickable(role = Role.Button, onClick = onClick)
                .semantics {
                    contentDescription = "$title: $subtitle"
                    role = Role.Button
                },
        leadingContent = { AccountAvatar(profile.avatarUrl, profile.email, size = 24.dp) },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        },
    )
}

@Composable
private fun MoreActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    tag: String,
    onClick: () -> Unit,
    showArrow: Boolean = true,
) {
    SettingsListItem(
        modifier =
            Modifier.testTag(tag).clickable(role = Role.Button, onClick = onClick).semantics {
                contentDescription = if (subtitle == null) title else "$title: $subtitle"
                role = Role.Button
            },
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent =
            if (showArrow) {
                { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) }
            } else null,
    )
}

@Composable
private fun SettingsCategoryRow(
    category: SettingsCategory,
    profile: AccountProfile,
    onClick: (SettingsCategory) -> Unit,
) {
    val title = category.title()
    val subtitle =
        when (category) {
            SettingsCategory.Categories -> stringResource(R.string.more_categories_subtitle)
            SettingsCategory.About -> stringResource(R.string.more_about_subtitle)
            else -> category.subtitle()
        }
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
