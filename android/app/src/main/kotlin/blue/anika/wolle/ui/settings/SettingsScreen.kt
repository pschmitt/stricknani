package blue.anika.wolle.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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
import blue.anika.wolle.ui.create.ImportOfferViewModel
import blue.anika.wolle.ui.create.moreCreateSubtitleRes

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
    onSearchClick: () -> Unit = {},
    adminAccess: AdminAccessViewModel = hiltViewModel(),
    accountAvatar: AccountAvatarViewModel = hiltViewModel(),
    importOfferViewModel: ImportOfferViewModel = hiltViewModel(),
) {
    val profile by accountAvatar.profile.collectAsStateWithLifecycle()
    val isAdmin by adminAccess.isAdmin.collectAsStateWithLifecycle()
    val importOffers by importOfferViewModel.offers.collectAsStateWithLifecycle()
    val layout = moreLayout(isAdmin)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.more_title)) },
                actions = {
                    IconButton(
                        onClick = onSearchClick,
                        modifier = Modifier.testTag("more-search"),
                    ) {
                        Icon(
                            PhosphorIcons.MagnifyingGlass,
                            contentDescription = stringResource(R.string.palette_open),
                        )
                    }
                },
            )
        }
    ) { innerPadding ->
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
                                    subtitle =
                                        stringResource(
                                            moreCreateSubtitleRes(
                                                importOffers,
                                                accountAvatar.isLocalMode,
                                            )
                                        ),
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
                Text(
                    stringResource(R.string.more_settings_header),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp).semantics { heading() },
                )
            }
            item {
                SettingsSingleItemCard {
                    MoreAccountRow(profile, accountAvatar.isLocalMode) {
                        onCategoryClick(SettingsCategory.Account)
                    }
                    layout.settings.forEach { category ->
                        SettingsCategoryRow(category, profile, onCategoryClick)
                    }
                    NotificationsRow()
                }
            }
            item {
                Text(
                    stringResource(R.string.more_data_header),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp).semantics { heading() },
                )
            }
            item {
                SettingsSingleItemCard {
                    layout.data.forEach { category ->
                        SettingsCategoryRow(category, profile, onCategoryClick)
                    }
                }
            }
            item {
                SettingsSingleItemCard {
                    layout.footer.forEach { category ->
                        SettingsCategoryRow(category, profile, onCategoryClick)
                    }
                }
            }
        }
    }
}

/** The web's "Notifications" setting; on Android the system owns it, so this opens it. */
@Composable
private fun NotificationsRow() {
    val context = LocalContext.current
    SettingsListItem(
        modifier =
            Modifier.fillMaxWidth().testTag("more-notifications").clickable {
                val appSettings =
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(appSettings)
                } catch (_: ActivityNotFoundException) {
                    context.startActivity(
                        Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            )
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            },
        leadingContent = { Icon(PhosphorIcons.Bell, contentDescription = null) },
        headlineContent = { Text(stringResource(R.string.settings_notifications_title)) },
        supportingContent = { Text(stringResource(R.string.settings_notifications_help)) },
        trailingContent = {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        },
    )
}

@Composable
private fun MoreAccountRow(profile: AccountProfile, localMode: Boolean, onClick: () -> Unit) {
    val title = stringResource(R.string.more_account_title)
    val subtitle =
        if (localMode) stringResource(R.string.local_only_title)
        else profile.email ?: stringResource(R.string.settings_category_account_subtitle)
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
