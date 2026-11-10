package blue.anika.wolle.ui.settings

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.R
import blue.anika.wolle.data.account.AvatarSource
import blue.anika.wolle.data.settings.AppLanguage
import blue.anika.wolle.data.settings.ThemeMode
import blue.anika.wolle.ui.admin.AdminAccessViewModel
import blue.anika.wolle.ui.common.PhosphorIcons
import blue.anika.wolle.ui.common.SegmentedControl
import blue.anika.wolle.ui.common.SegmentedOption
import blue.anika.wolle.ui.oidc.LinkedLoginsCard
import blue.anika.wolle.ui.oidc.LinkedLoginsPhase
import blue.anika.wolle.ui.oidc.LinkedLoginsViewModel
import blue.anika.wolle.ui.oidc.ReauthSheet

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
    var showUseServerDialog by remember { mutableStateOf(false) }
    val localMode = viewModel.isLocalMode
    val linkedLogins: LinkedLoginsViewModel = hiltViewModel()
    val linkedLoginsState by linkedLogins.state.collectAsStateWithLifecycle()
    val avatar: AccountAvatarViewModel = hiltViewModel()
    val profile by avatar.profile.collectAsStateWithLifecycle()
    val adminAccess: AdminAccessViewModel = hiltViewModel()
    val isAdmin by adminAccess.isAdmin.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val securityModel: AccountSecurityViewModel = hiltViewModel()
    val security by securityModel.state.collectAsStateWithLifecycle()
    val tokenSheet by securityModel.sheet.collectAsStateWithLifecycle()
    // A token is confirmed through a login provider the account already has.
    val tokenProvider =
        linkedLoginsState.identities.firstNotNullOfOrNull { id ->
            linkedLoginsState.providers.firstOrNull { it.id == id.provider }
        }
    val errorReportingModel: ErrorReportingViewModel = hiltViewModel()
    val errorReporting by errorReportingModel.state.collectAsStateWithLifecycle()
    val errorReportingBusy by errorReportingModel.busy.collectAsStateWithLifecycle()
    val errorReportingFailed by errorReportingModel.saveFailed.collectAsStateWithLifecycle()

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
            modifier =
                Modifier.fillMaxSize().padding(innerPadding).testTag("account-settings-list"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Administrators get the way to Admin as its own card, above the profile.
            if (isAdmin && !localMode) {
                item { AdminCard(onClick = { onOpenCategory(SettingsCategory.Admin) }) }
            }
            // Local-only mode: no profile, password or sessions; just where the data lives.
            if (localMode) {
                item { LocalOnlyCard(onUseServer = { showUseServerDialog = true }) }
            }
            // Profile: the picture (change/remove need a server with bearer account access), the
            // email and the role.
            if (!localMode)
                item {
                    SettingsGroupCard(
                        title = stringResource(R.string.account_profile_title),
                        icon = PhosphorIcons.UserCircle,
                    ) {
                        ProfilePicture(
                            avatarUrl = profile.avatarUrl,
                            email = profile.email,
                            editable = security.available,
                            busy = security.avatarBusy,
                            onPicked = securityModel::uploadAvatar,
                            onCameraDenied = securityModel::cameraDenied,
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                ProfileEmail(
                                    profile.email
                                        ?: stringResource(R.string.account_settings_not_connected)
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
                        if (
                            security.available &&
                                profile.avatarUrl != null &&
                                security.avatarSource != AvatarSource.GRAVATAR
                        ) {
                            RemovePictureButton(
                                enabled = !security.avatarBusy,
                                onClick = securityModel::askRemoveAvatar,
                            )
                        }
                        if (security.available && security.gravatarEnabled) {
                            GravatarToggleRow(
                                checked = security.useGravatar,
                                enabled = !security.gravatarBusy,
                                onChange = securityModel::setUseGravatar,
                            )
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
                            groupLabel = stringResource(R.string.account_pref_language),
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
                            groupLabel = stringResource(R.string.account_pref_appearance),
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
                            groupLabel = stringResource(R.string.account_pref_text_size),
                            selected = textStep,
                            label = { it.textSizeLabel() },
                            onSelect = viewModel::setReadingTextStep,
                        )
                    }
                    if (errorReporting.visible) {
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        ErrorReportingToggleRow(
                            checked = errorReporting.enabled,
                            enabled = !errorReportingBusy,
                            saveFailed = errorReportingFailed,
                            onChange = errorReportingModel::set,
                        )
                    }
                }
            }
            // Only when the server lists a sign-in provider (OIDC); hidden otherwise.
            if (!localMode && linkedLoginsState.phase != LinkedLoginsPhase.Hidden) {
                item { LinkedLoginsCard(linkedLogins) }
            }
            if (!localMode && security.available) {
                item {
                    SettingsGroupCard(
                        title = stringResource(R.string.account_security_title),
                        icon = PhosphorIcons.ShieldCheck,
                    ) {
                        PasswordRow(
                            open = security.passwordOpen,
                            busy = security.passwordBusy,
                            errors = security.passwordErrors,
                            onOpenChange = securityModel::setPasswordOpen,
                            onSubmit = securityModel::changePassword,
                        )
                    }
                }
                item {
                    SettingsGroupCard(
                        title = stringResource(R.string.account_tokens_title),
                        icon = Icons.Filled.Smartphone,
                        headerAction = {
                            NewTokenAction(
                                visible = !security.createOpen,
                                onClick = { securityModel.setCreateOpen(true) },
                            )
                        },
                    ) {
                        NewTokenForm(
                            open = security.createOpen,
                            busy = security.createBusy,
                            passwordLogin = linkedLoginsState.passwordEnabled,
                            provider = tokenProvider,
                            errors = security.createErrors,
                            onCancel = { securityModel.setCreateOpen(false) },
                            onCreate = securityModel::createToken,
                            onCreateWithProvider = { name ->
                                tokenProvider?.let { securityModel.createTokenWithProvider(name, it) }
                            },
                        )
                        when {
                            security.tokenLoad == TokenLoad.Failed -> {
                                Text(
                                    stringResource(R.string.account_tokens_load_failed),
                                    modifier =
                                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                TextButton(
                                    onClick = securityModel::load,
                                    modifier = Modifier.padding(horizontal = 8.dp),
                                ) {
                                    Text(stringResource(R.string.account_tokens_retry))
                                }
                            }
                            security.tokens.isEmpty() && security.tokenLoad == TokenLoad.Loaded ->
                                Text(
                                    stringResource(R.string.account_tokens_empty),
                                    modifier =
                                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            else ->
                                security.tokens.forEach { token ->
                                    TokenRow(
                                        token = token,
                                        isThisDevice = token.id == security.currentTokenId,
                                        revoking = security.revokingId == token.id,
                                        onRevoke = { securityModel.askRevoke(token) },
                                    )
                                }
                        }
                    }
                }
            }
            item {
                SettingsGroupCard(
                    title = stringResource(R.string.account_data_title),
                    icon = PhosphorIcons.HardDrives,
                ) {
                    if (!localMode) {
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
                    }
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
            if (!localMode) item { DeleteAccountCard(onDeleted = viewModel::accountDeleted) }
            if (!localMode)
                item {
                    SettingsSingleItemCard {
                        OutlinedButton(
                            onClick = { showSignOutDialog = true },
                            modifier =
                                Modifier.fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
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

    if (security.confirmRemoveAvatar) {
        RemoveAvatarDialog(
            onConfirm = securityModel::confirmRemoveAvatar,
            onDismiss = securityModel::dismissRemoveAvatar,
        )
    }
    security.confirmRevoke?.let { token ->
        RevokeTokenDialog(
            token = token,
            isThisDevice = token.id == security.currentTokenId,
            onConfirm = securityModel::confirmRevoke,
            onDismiss = securityModel::dismissRevoke,
        )
    }
    security.revealed?.let { created ->
        RevealedTokenDialog(
            name = created.name,
            token = created.token,
            onDone = securityModel::dismissRevealed,
        )
    }
    tokenSheet?.let {
        ReauthSheet(
            state = it,
            onSubmitPassword = securityModel::submitPassword,
            onUseProvider = securityModel::confirmWithProvider,
            onDismiss = securityModel::dismissSheet,
            message = R.string.account_token_reauth_text,
        )
    }
    security.signOutNotice?.let { notice ->
        SignedOutDialog(notice) { securityModel.confirmSignOut(viewModel::signOut) }
    }

    if (showUseServerDialog) {
        UseServerDialog(
            onConfirm = {
                showUseServerDialog = false
                // The same sign-out as on a server account: it clears the app's cache and returns
                // to onboarding, and leaves the local server's data directory alone.
                viewModel.signOut()
            },
            onDismiss = { showUseServerDialog = false },
        )
    }

    if (showSignOutDialog) {
        SignOutDialog(
            onConfirm = {
                showSignOutDialog = false
                viewModel.signOut()
            },
            onDismiss = { showSignOutDialog = false },
        )
    }
}

/** Local-only mode: where the data lives, and the way to a server. */
@Composable
private fun LocalOnlyCard(onUseServer: () -> Unit) {
    SettingsGroupCard(
        title = stringResource(R.string.local_only_title),
        icon = Icons.Filled.Smartphone,
    ) {
        Text(
            stringResource(R.string.local_only_text),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        OutlinedButton(
            onClick = onUseServer,
            modifier =
                Modifier.fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("local-only-use-server"),
        ) {
            Text(stringResource(R.string.local_only_use_server))
        }
    }
}

@Composable
private fun UseServerDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.local_only_use_server)) },
        text = { Text(stringResource(R.string.local_only_use_server_confirm)) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("local-only-confirm")) {
                Text(stringResource(R.string.local_only_use_server))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
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

/** The shared [SegmentedControl] for every preference. */
@Composable
private fun <T> SegmentedChoice(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    groupLabel: String,
    icon: (@Composable (T) -> ImageVector)? = null,
) {
    SegmentedControl(
        options =
            options.map { option ->
                SegmentedOption(option, label(option), icon = icon?.invoke(option))
            },
        selected = selected,
        onSelect = onSelect,
        label = groupLabel,
        fullWidth = true,
    )
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

/** Administrators only: the way to Admin, styled like Home's stash card. */
@Composable
private fun AdminCard(onClick: () -> Unit) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth().testTag("account-admin-card"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Box(
                Modifier.size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    SettingsCategory.Admin.icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.account_admin_card_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.account_admin_card_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

/**
 * The account email beside the avatar: the name before the "@" as the title and the domain as a
 * quieter line under it, each on one line (ellipsized), instead of a long address breaking at
 * arbitrary characters. Screen readers get the whole address once.
 */
@Composable
private fun ProfileEmail(email: String) {
    val at = email.lastIndexOf('@')
    val name = if (at > 0) email.substring(0, at) else email
    val domain = if (at > 0) email.substring(at) else null
    Column(Modifier.clearAndSetSemantics { contentDescription = email }) {
        Text(
            name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (domain != null)
            Text(
                domain,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
    }
}
