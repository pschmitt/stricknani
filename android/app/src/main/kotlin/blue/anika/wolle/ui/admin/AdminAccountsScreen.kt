package blue.anika.wolle.ui.admin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.LockReset
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.R
import blue.anika.wolle.data.api.dto.AdminUserDto
import blue.anika.wolle.data.repository.AdminFailure
import blue.anika.wolle.ui.common.EmptyState
import blue.anika.wolle.ui.common.MdiIcons
import blue.anika.wolle.ui.common.PhosphorIcons
import blue.anika.wolle.ui.common.YarnLoader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminAccountsScreen(
    onBack: () -> Unit,
    onOpenUserProjects: (userId: Long) -> Unit = {},
    onOpenUserYarns: (userId: Long) -> Unit = {},
    viewModel: AdminAccountsViewModel = hiltViewModel(),
    identitiesViewModel: AdminIdentitiesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val identitiesState by identitiesViewModel.state.collectAsStateWithLifecycle()
    val form by viewModel.form.collectAsStateWithLifecycle()
    var toToggle by remember { mutableStateOf<AdminUserDto?>(null) }
    var toReset by remember { mutableStateOf<AdminUserDto?>(null) }
    var resetResult by remember { mutableStateOf<Pair<AdminUserDto, String>?>(null) }
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var toImpersonate by remember { mutableStateOf<AdminUserDto?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.admin_accounts_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_nav_back),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            if (state.phase == AccountsPhase.Ready) {
                ExtendedFloatingActionButton(
                    onClick = {
                        viewModel.clearForm()
                        showAdd = true
                    },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.admin_accounts_add)) },
                    modifier = Modifier.testTag("admin-accounts-add"),
                )
            }
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding).testTag("admin-accounts-screen")) {
            when (val phase = state.phase) {
                AccountsPhase.Loading ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        YarnLoader()
                    }
                is AccountsPhase.Failed ->
                    AdminFailureState(phase.failure, onRetry = viewModel::retry, onBack = onBack)
                AccountsPhase.Ready ->
                    PullToRefreshBox(
                        isRefreshing = state.isRefreshing,
                        onRefresh = viewModel::refresh,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        if (state.users.isEmpty()) {
                            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                                EmptyState(
                                    icon = PhosphorIcons.UsersThree,
                                    title = stringResource(R.string.admin_accounts_empty_title),
                                    subtitle =
                                        stringResource(R.string.admin_accounts_empty_subtitle),
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize().testTag("admin-accounts-list"),
                                // Room at the bottom so the FAB never covers the last row.
                                contentPadding =
                                    PaddingValues(
                                        start = 16.dp,
                                        end = 16.dp,
                                        top = 8.dp,
                                        bottom = 96.dp,
                                    ),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(state.users, key = { it.id }) { user ->
                                    AccountRow(
                                        user = user,
                                        isYou =
                                            isCurrentUser(
                                                user,
                                                state.currentEmail,
                                                state.currentUserId,
                                            ),
                                        busy = state.busyUserId == user.id,
                                        avatarUrl = viewModel.avatarUrl(user.avatarUrl),
                                        onToggleActive = { toToggle = user },
                                        // Only when the server lists a sign-in provider (OIDC)
                                        // and the account has a linked identity (like the web).
                                        onShowLogins =
                                            if (
                                                identitiesState.available &&
                                                    (user.linkedIdentities ?: 1) > 0
                                            ) {
                                                { identitiesViewModel.open(user) }
                                            } else null,
                                        onOpenProjects = { onOpenUserProjects(user.id) },
                                        onOpenYarns = { onOpenUserYarns(user.id) },
                                        onResetPassword = {
                                            viewModel.clearForm()
                                            toReset = user
                                        },
                                        // Same entries as the web; the app switches into an
                                        // impersonation session itself (restarts as that account).
                                        onImpersonate =
                                            if (viewModel.canImpersonateUser(user)) {
                                                { toImpersonate = user }
                                            } else null,
                                    )
                                }
                            }
                        }
                    }
            }
        }
    }

    toToggle?.let { user ->
        val enabling = !user.active
        AlertDialog(
            onDismissRequest = { toToggle = null },
            title = {
                Text(
                    stringResource(
                        if (enabling) R.string.admin_accounts_enable_title
                        else R.string.admin_accounts_disable_title,
                        user.email,
                    )
                )
            },
            text = {
                Text(
                    stringResource(
                        if (enabling) R.string.admin_accounts_enable_text
                        else R.string.admin_accounts_disable_text
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        toToggle = null
                        viewModel.setActive(user, enabling)
                    }
                ) {
                    Text(
                        stringResource(
                            if (enabling) R.string.admin_accounts_enable
                            else R.string.admin_accounts_disable
                        ),
                        color =
                            if (enabling) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { toToggle = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    toImpersonate?.let { user ->
        AlertDialog(
            onDismissRequest = { toImpersonate = null },
            title = { Text(stringResource(R.string.admin_accounts_impersonate_title)) },
            text = { Text(stringResource(R.string.admin_accounts_impersonate_text, user.email)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        toImpersonate = null
                        viewModel.impersonate(user)
                    },
                    modifier = Modifier.testTag("admin-impersonate-confirm"),
                ) {
                    Text(stringResource(R.string.admin_accounts_impersonate))
                }
            },
            dismissButton = {
                TextButton(onClick = { toImpersonate = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    toReset?.let { user ->
        ResetPasswordDialog(
            user = user,
            form = form,
            onConfirm = { password ->
                viewModel.resetPassword(user, password) {
                    toReset = null
                    resetResult = user to password
                }
            },
            onDismiss = {
                toReset = null
                viewModel.clearForm()
            },
            onCopied = viewModel::copied,
        )
    }

    resetResult?.let { (user, password) ->
        PasswordResultDialog(
            email = user.email,
            password = password,
            onCopied = viewModel::copied,
            onDismiss = { resetResult = null },
        )
    }

    AdminIdentitiesSheet(identitiesViewModel)

    if (showAdd) {
        AddAccountSheet(
            form = form,
            onSave = { email, password, isAdmin ->
                viewModel.createUser(email, password, isAdmin) { showAdd = false }
            },
            onDismiss = {
                showAdd = false
                viewModel.clearForm()
            },
            onCopied = viewModel::copied,
        )
    }
}

@Composable
private fun AccountRow(
    user: AdminUserDto,
    isYou: Boolean,
    busy: Boolean,
    avatarUrl: String?,
    onToggleActive: () -> Unit,
    onShowLogins: (() -> Unit)?,
    onOpenProjects: () -> Unit,
    onOpenYarns: () -> Unit,
    onResetPassword: () -> Unit,
    onImpersonate: (() -> Unit)?,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val disabledText = stringResource(R.string.admin_accounts_badge_disabled)
    // Like the web's Role and Status columns: every row says Administrator or Member, Active or
    // Disabled.
    val roleText =
        stringResource(
            if (user.isAdmin) R.string.admin_accounts_badge_admin
            else R.string.admin_accounts_badge_member
        )
    val statusText =
        if (user.active) stringResource(R.string.admin_accounts_badge_active) else disabledText
    val description = buildString {
        append(user.email)
        if (isYou) append(", ").append(stringResource(R.string.admin_accounts_badge_you))
        append(", ").append(roleText)
        append(", ").append(statusText)
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier =
            Modifier.fillMaxWidth().heightIn(min = 64.dp).semantics(mergeDescendants = true) {
                contentDescription = description
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        ) {
            AdminAvatar(avatarUrl, user.email, size = 44.dp, dimmed = !user.active)
            Column(
                Modifier.weight(1f)
                    .padding(horizontal = 12.dp)
                    .alpha(if (user.active) 1f else 0.55f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    user.email,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (isYou) {
                        Pill(
                            stringResource(R.string.admin_accounts_badge_you),
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    Pill(
                        roleText,
                        if (user.isAdmin) MaterialTheme.colorScheme.tertiaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHighest,
                        if (user.isAdmin) MaterialTheme.colorScheme.onTertiaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Pill(
                        statusText,
                        if (user.active) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHighest,
                        if (user.active) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    enabled = !busy,
                    modifier = Modifier.testTag("admin-account-menu-${user.id}"),
                ) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription =
                            stringResource(R.string.admin_accounts_menu, user.email),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (!isYou) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (user.active) R.string.admin_accounts_disable
                                        else R.string.admin_accounts_enable
                                    )
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    if (user.active) Icons.Filled.Block
                                    else Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onToggleActive()
                            },
                        )
                    }
                    if (onShowLogins != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.oidc_admin_menu)) },
                            leadingIcon = {
                                Icon(PhosphorIcons.LockKey, contentDescription = null)
                            },
                            onClick = {
                                menuOpen = false
                                onShowLogins()
                            },
                        )
                    }
                    // Same entries and order as the web menu: the user's projects and yarns.
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.destination_label_projects)) },
                        leadingIcon = { Icon(PhosphorIcons.Hoodie, contentDescription = null) },
                        modifier = Modifier.testTag("admin-account-projects-${user.id}"),
                        onClick = {
                            menuOpen = false
                            onOpenProjects()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.destination_label_yarns)) },
                        leadingIcon = { Icon(MdiIcons.YarnBall, contentDescription = null) },
                        modifier = Modifier.testTag("admin-account-yarns-${user.id}"),
                        onClick = {
                            menuOpen = false
                            onOpenYarns()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.admin_accounts_reset_password)) },
                        leadingIcon = { Icon(Icons.Filled.LockReset, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onResetPassword()
                        },
                    )
                    if (onImpersonate != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.admin_accounts_impersonate)) },
                            leadingIcon = {
                                Icon(PhosphorIcons.UsersThree, contentDescription = null)
                            },
                            modifier = Modifier.testTag("admin-account-impersonate-${user.id}"),
                            onClick = {
                                menuOpen = false
                                onImpersonate()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Pill(
    text: String,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
) {
    Surface(shape = MaterialTheme.shapes.small, color = container, contentColor = content) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** A password field with show/hide, "make one for me" and copy, shared by add and reset. */
@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    errorText: String?,
    onCopied: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            isError = errorText != null,
            supportingText = {
                Text(errorText ?: stringResource(R.string.admin_password_hint, MIN_PASSWORD_LENGTH))
            },
            visualTransformation =
                if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription =
                            stringResource(
                                if (visible) R.string.admin_password_hide
                                else R.string.admin_password_show
                            ),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    onValueChange(generateTemporaryPassword())
                    visible = true
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null)
                Text(
                    stringResource(R.string.admin_password_generate),
                    Modifier.padding(start = 8.dp),
                )
            }
            if (value.isNotEmpty()) {
                OutlinedButton(
                    onClick = {
                        copyPassword(context, value)
                        onCopied()
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null)
                    Text(
                        stringResource(R.string.admin_password_copy),
                        Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

/** Copies a password flagged as sensitive, so Android hides it from the clipboard preview. */
private fun copyPassword(context: Context, password: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip =
        ClipData.newPlainText(context.getString(R.string.admin_password_clip_label), password)
    clip.description.extras =
        PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    clipboard.setPrimaryClip(clip)
}

@Composable
private fun ResetPasswordDialog(
    user: AdminUserDto,
    form: FormState,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    onCopied: () -> Unit,
) {
    var password by rememberSaveable(user.id) { mutableStateOf("") }
    val saving = form == FormState.Saving
    val tooShort = password.isNotEmpty() && password.length < MIN_PASSWORD_LENGTH
    val serverError = (form as? FormState.Failed)?.failure
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.admin_accounts_reset_title, user.email)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.admin_accounts_reset_text),
                    style = MaterialTheme.typography.bodyMedium,
                )
                PasswordField(
                    value = password,
                    onValueChange = { password = it },
                    label = stringResource(R.string.admin_password_new),
                    errorText = serverError?.let { stringResource(it.messageRes()) },
                    onCopied = onCopied,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(password) },
                enabled = !saving && password.length >= MIN_PASSWORD_LENGTH && !tooShort,
            ) {
                Text(stringResource(R.string.admin_accounts_reset_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

/** Shown after a successful reset: the new password with a copy button, to hand to its owner. */
@Composable
private fun PasswordResultDialog(
    email: String,
    password: String,
    onCopied: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.admin_accounts_result_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.admin_accounts_result_text, email))
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    Text(
                        password,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                    )
                }
                OutlinedButton(
                    onClick = {
                        copyPassword(context, password)
                        onCopied()
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null)
                    Text(
                        stringResource(R.string.admin_password_copy),
                        Modifier.padding(start = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddAccountSheet(
    form: FormState,
    onSave: (email: String, password: String, isAdmin: Boolean) -> Unit,
    onDismiss: () -> Unit,
    onCopied: () -> Unit,
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var isAdmin by rememberSaveable { mutableStateOf(false) }
    val saving = form == FormState.Saving
    val failure = (form as? FormState.Failed)?.failure
    val emailError =
        when (failure) {
            AdminFailure.EmailInUse,
            AdminFailure.InvalidEmail -> failure
            else -> null
        }
    val passwordError =
        when (failure) {
            AdminFailure.PasswordTooShort,
            AdminFailure.PasswordTooCommon,
            AdminFailure.PasswordTooLong -> failure
            else -> null
        }
    val otherError = if (emailError == null && passwordError == null) failure else null
    val valid = email.contains('@') && password.length >= MIN_PASSWORD_LENGTH

    ModalBottomSheet(
        onDismissRequest = { if (!saving) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .testTag("admin-add-account"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.admin_accounts_add_title),
                style = MaterialTheme.typography.titleLarge,
            )
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text(stringResource(R.string.admin_accounts_email)) },
                singleLine = true,
                isError = emailError != null,
                supportingText = emailError?.let { { Text(stringResource(it.messageRes())) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            PasswordField(
                value = password,
                onValueChange = { password = it },
                label = stringResource(R.string.admin_password_label),
                errorText = passwordError?.let { stringResource(it.messageRes()) },
                onCopied = onCopied,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier.fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clip(MaterialTheme.shapes.small)
                        .toggleable(
                            value = isAdmin,
                            role = Role.Switch,
                            onValueChange = { isAdmin = it },
                        ),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.admin_accounts_make_admin),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(R.string.admin_accounts_make_admin_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = isAdmin, onCheckedChange = null)
            }
            otherError?.let {
                Text(
                    stringResource(it.messageRes()),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Button(
                onClick = { onSave(email, password, isAdmin) },
                enabled = valid && !saving,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                if (saving) {
                    YarnLoader(size = 26.dp, modifier = Modifier.padding(end = 8.dp))
                }
                Text(
                    stringResource(R.string.admin_accounts_create),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}
