package blue.anika.wolle.ui.settings

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.R
import blue.anika.wolle.data.settings.AppLanguage
import blue.anika.wolle.data.settings.ThemeMode
import blue.anika.wolle.ui.admin.AdminAccessViewModel
import blue.anika.wolle.ui.common.AccountAvatar
import blue.anika.wolle.ui.common.PhosphorIcons
import blue.anika.wolle.ui.oidc.LinkedLoginsCard
import blue.anika.wolle.ui.oidc.LinkedLoginsPhase
import blue.anika.wolle.ui.oidc.LinkedLoginsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccountSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel,
    onOpenCategory: (SettingsCategory) -> Unit = {},
) {
    val serverUrl by viewModel.serverUrl.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val appLanguage by viewModel.appLanguage.collectAsStateWithLifecycle()
    val textStep by viewModel.readingTextStep.collectAsStateWithLifecycle()
    var showSignOutDialog by remember { mutableStateOf(false) }
    val linkedLogins: LinkedLoginsViewModel = hiltViewModel()
    val linkedLoginsState by linkedLogins.state.collectAsStateWithLifecycle()
    val avatar: AccountAvatarViewModel = hiltViewModel()
    val profile by avatar.profile.collectAsStateWithLifecycle()
    val adminAccess: AdminAccessViewModel = hiltViewModel()
    val isAdmin by adminAccess.isAdmin.collectAsStateWithLifecycle()
    val activity = LocalActivity.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(SettingsCategory.Account.title()) },
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
            // Profile: the picture, the email and the role. Changing or removing the picture is a
            // browser-session-only server route, so it is not offered here.
            item {
                SettingsGroupCard(
                    title = stringResource(R.string.account_profile_title),
                    icon = PhosphorIcons.UserCircle,
                ) {
                    Row(
                        modifier =
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        AccountAvatar(profile.avatarUrl, profile.email, size = 80.dp)
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                profile.email
                                    ?: stringResource(R.string.account_settings_not_connected),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ) {
                                Text(
                                    stringResource(
                                        if (isAdmin) R.string.account_role_admin
                                        else R.string.account_role_member
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier =
                                        Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
            item {
                SettingsGroupCard(
                    title = stringResource(R.string.account_preferences_title),
                    icon = PhosphorIcons.SlidersHorizontal,
                ) {
                    PreferenceRow(label = stringResource(R.string.account_pref_language)) {
                        SegmentedChoice(
                            options = AppLanguage.entries,
                            selected = appLanguage,
                            label = { it.accountLabel() },
                            onSelect = {
                                viewModel.setAppLanguage(it)
                                activity?.recreate()
                            },
                        )
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    PreferenceRow(label = stringResource(R.string.account_pref_appearance)) {
                        SegmentedChoice(
                            options = ThemeMode.entries,
                            selected = themeMode,
                            label = { it.accountLabel() },
                            icon = { it.accountIcon() },
                            onSelect = viewModel::setThemeMode,
                        )
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    PreferenceRow(
                        label = stringResource(R.string.account_pref_text_size),
                        hint = stringResource(R.string.account_pref_text_size_hint),
                    ) {
                        SegmentedChoice(
                            options = listOf(0, 1, 2),
                            selected = textStep,
                            label = { it.textSizeLabel() },
                            onSelect = viewModel::setReadingTextStep,
                        )
                    }
                }
            }
            // Only when the server lists a sign-in provider (OIDC); hidden otherwise.
            if (linkedLoginsState.phase != LinkedLoginsPhase.Hidden) {
                item { LinkedLoginsCard(linkedLogins) }
            }
            item {
                SettingsGroupCard(
                    title = stringResource(R.string.account_data_title),
                    icon = PhosphorIcons.HardDrives,
                ) {
                    SettingsListItem(
                        headlineContent = {
                            Text(stringResource(R.string.account_settings_server_url_label))
                        },
                        supportingContent = {
                            Text(
                                serverUrl.ifBlank {
                                    stringResource(R.string.account_settings_not_connected)
                                }
                            )
                        },
                    )
                    listOf(SettingsCategory.Sync, SettingsCategory.Backup, SettingsCategory.About)
                        .forEach { category ->
                            SettingsListItem(
                                modifier =
                                    Modifier.clickable(role = Role.Button) {
                                        onOpenCategory(category)
                                    },
                                leadingContent = { Icon(category.icon, contentDescription = null) },
                                headlineContent = { Text(category.title()) },
                                supportingContent = { Text(category.subtitle()) },
                                trailingContent = {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = null,
                                    )
                                },
                            )
                        }
                }
            }
            item {
                SettingsSingleItemCard {
                    OutlinedButton(
                        onClick = { showSignOutDialog = true },
                        modifier =
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Logout,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Text(stringResource(R.string.settings_sign_out))
                    }
                    Text(
                        stringResource(R.string.settings_sign_out_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    )
                }
            }
        }
    }

    if (showSignOutDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutDialog = false },
            title = { Text(stringResource(R.string.account_settings_sign_out_dialog_title)) },
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
                TextButton(onClick = { showSignOutDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

/** A label with its control: full width below the label on phones, to the right on wide screens. */
@Composable
private fun PreferenceRow(
    label: String,
    hint: String? = null,
    control: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= 600.dp
        val text: @Composable (Modifier) -> Unit = { modifier ->
            Column(modifier) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                hint?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (wide) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                text(Modifier.weight(1f))
                Box(Modifier.width(360.dp)) { control() }
            }
        } else {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                text(Modifier)
                control()
            }
        }
    }
}

/** The one segmented-button style used by every preference. */
@Composable
private fun <T> SegmentedChoice(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    icon: (@Composable (T) -> ImageVector)? = null,
) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                icon = {
                    SegmentedButtonDefaults.Icon(active = option == selected) {
                        icon?.let {
                            Icon(
                                it(option),
                                contentDescription = null,
                                modifier = Modifier.size(SegmentedButtonDefaults.IconSize),
                            )
                        }
                    }
                },
                label = { Text(label(option), maxLines = 1) },
            )
        }
    }
}

@Composable
private fun AppLanguage.accountLabel(): String =
    when (this) {
        AppLanguage.SYSTEM -> stringResource(R.string.account_option_system)
        AppLanguage.ENGLISH -> "English"
        AppLanguage.GERMAN -> "Deutsch"
    }

@Composable
private fun ThemeMode.accountLabel(): String =
    when (this) {
        ThemeMode.SYSTEM -> stringResource(R.string.account_option_system)
        ThemeMode.LIGHT -> stringResource(R.string.appearance_settings_theme_light_label)
        ThemeMode.DARK -> stringResource(R.string.appearance_settings_theme_dark_label)
    }

private fun ThemeMode.accountIcon(): ImageVector =
    when (this) {
        ThemeMode.SYSTEM -> Icons.Filled.SettingsBrightness
        ThemeMode.LIGHT -> Icons.Filled.LightMode
        ThemeMode.DARK -> Icons.Filled.DarkMode
    }

@Composable
private fun Int.textSizeLabel(): String =
    stringResource(
        when (this) {
            0 -> R.string.account_text_small
            2 -> R.string.account_text_large
            else -> R.string.account_text_default
        }
    )
