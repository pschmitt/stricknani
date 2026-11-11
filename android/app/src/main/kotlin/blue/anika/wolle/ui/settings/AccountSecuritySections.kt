package blue.anika.wolle.ui.settings

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import blue.anika.wolle.R
import blue.anika.wolle.data.account.ApiTokenDto
import blue.anika.wolle.data.account.formatTokenDate
import blue.anika.wolle.data.oidc.OidcProvider
import blue.anika.wolle.ui.common.AccountAvatar
import blue.anika.wolle.ui.common.PhosphorIcons
import blue.anika.wolle.ui.common.rememberPhotoCapture

/**
 * Profile picture with a camera badge at its bottom-end (opens the system photo picker) and, when a
 * picture exists, a quiet "Remove picture" text button underneath. [content] is the email/role
 * column beside the avatar.
 */
@Composable
internal fun ProfilePicture(
    avatarUrl: String?,
    email: String?,
    editable: Boolean,
    busy: Boolean,
    onPicked: (String) -> Unit,
    onCameraDenied: () -> Unit,
    content: @Composable () -> Unit,
) {
    val changeLabel = stringResource(R.string.account_avatar_change)
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) onPicked(uri.toString())
        }
    val capture =
        rememberPhotoCapture(
            onPhoto = { uri -> onPicked(uri.toString()) },
            onPermissionDenied = onCameraDenied,
        )
    var sourceMenu by remember { mutableStateOf(false) }
    val pickFromGallery = {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 90dp holds the 80dp avatar and the 48dp badge target overlapping its bottom-end corner,
        // so the whole badge stays inside this box's bounds (and inside its hit area).
        Box(Modifier.size(90.dp)) {
            AccountAvatar(
                avatarUrl,
                email,
                size = 80.dp,
                modifier = Modifier.align(Alignment.TopStart),
            )
            if (busy) {
                val uploading = stringResource(R.string.account_avatar_uploading)
                Box(
                    modifier =
                        Modifier.align(Alignment.TopStart)
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f))
                            .semantics {
                                contentDescription = uploading
                                liveRegion = LiveRegionMode.Polite
                            },
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        color = Color.White,
                        strokeWidth = 3.dp,
                    )
                }
            }
            if (editable) {
                Box(
                    modifier =
                        Modifier.align(Alignment.BottomEnd)
                            .size(48.dp)
                            .clip(CircleShape)
                            .clickable(
                                enabled = !busy,
                                role = Role.Button,
                                onClickLabel = stringResource(R.string.account_avatar_change),
                            ) {
                                // With a camera app around, ask where the picture comes from.
                                if (capture.available) sourceMenu = true else pickFromGallery()
                            }
                            .semantics { contentDescription = changeLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier =
                            Modifier.size(32.dp)
                                .border(
                                    2.dp,
                                    MaterialTheme.colorScheme.surfaceContainer,
                                    CircleShape,
                                )
                                .background(MaterialTheme.colorScheme.primary, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.PhotoCamera,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
            if (editable) {
                DropdownMenu(expanded = sourceMenu, onDismissRequest = { sourceMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.photo_choose)) },
                        leadingIcon = { Icon(PhosphorIcons.Images, contentDescription = null) },
                        onClick = {
                            sourceMenu = false
                            pickFromGallery()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.photo_take)) },
                        leadingIcon = { Icon(PhosphorIcons.Camera, contentDescription = null) },
                        onClick = {
                            sourceMenu = false
                            capture.launch()
                        },
                    )
                }
            }
        }
        content()
    }
}

/**
 * "Use Gravatar when no picture is uploaded": the whole row toggles. The server (never the app)
 * fetches the image from Gravatar using the account email, which the supporting line says.
 */
@Composable
internal fun GravatarToggleRow(checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    SettingsListItem(
        modifier =
            Modifier.fillMaxWidth()
                .toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = onChange,
                ),
        headlineContent = { Text(stringResource(R.string.account_gravatar_toggle)) },
        supportingContent = { Text(stringResource(R.string.account_gravatar_toggle_help)) },
        trailingContent = { Switch(checked = checked, enabled = enabled, onCheckedChange = null) },
    )
}

/**
 * "Remove location and camera data from new photos": the whole row toggles. The server removes the
 * EXIF/XMP metadata of photos the account uploads from now on; stored photos are never changed.
 */
@Composable
internal fun StripPhotoMetadataToggleRow(
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    SettingsListItem(
        modifier =
            Modifier.fillMaxWidth()
                .testTag("strip-photo-metadata-toggle")
                .toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = onChange,
                ),
        headlineContent = { Text(stringResource(R.string.account_strip_metadata_toggle)) },
        supportingContent = { Text(stringResource(R.string.account_strip_metadata_toggle_help)) },
        trailingContent = { Switch(checked = checked, enabled = enabled, onCheckedChange = null) },
    )
}

/**
 * "Send error reports": the whole row toggles. Shown only when the server offers error reporting in
 * a build that includes it; the supporting line says what is (not) sent.
 */
@Composable
internal fun ErrorReportingToggleRow(
    checked: Boolean,
    enabled: Boolean,
    saveFailed: Boolean,
    onChange: (Boolean) -> Unit,
) {
    SettingsListItem(
        modifier =
            Modifier.fillMaxWidth()
                .testTag("error-reporting-toggle")
                .toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = onChange,
                ),
        headlineContent = { Text(stringResource(R.string.account_error_reports_toggle)) },
        supportingContent = {
            Text(
                stringResource(
                    if (saveFailed) R.string.account_error_reports_save_failed
                    else R.string.account_error_reports_toggle_help
                )
            )
        },
        trailingContent = { Switch(checked = checked, enabled = enabled, onCheckedChange = null) },
    )
}

/** The quiet "Remove picture" button; the caller shows it only when an avatar exists. */
@Composable
internal fun RemovePictureButton(enabled: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.padding(horizontal = 8.dp),
    ) {
        Icon(
            PhosphorIcons.Trash,
            contentDescription = null,
            modifier = Modifier.padding(end = 8.dp).size(18.dp),
        )
        Text(stringResource(R.string.account_avatar_remove))
    }
}

/** "Password" row: notice text and a secondary button that expands the form inline. */
@Composable
internal fun PasswordRow(
    open: Boolean,
    busy: Boolean,
    errors: PasswordFormErrors,
    onOpenChange: (Boolean) -> Unit,
    onSubmit: (current: String, new: String, confirm: String) -> Unit,
) {
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    fun close() {
        current = ""
        new = ""
        confirm = ""
        onOpenChange(false)
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.account_password_label),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            stringResource(R.string.account_password_notice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!open) {
            OutlinedButton(onClick = { onOpenChange(true) }) {
                Text(stringResource(R.string.account_password_change))
            }
        }
        AnimatedVisibility(
            visible = open,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SecretField(
                    current,
                    { current = it },
                    stringResource(R.string.account_password_current),
                    errors.current,
                    enabled = !busy,
                )
                SecretField(
                    new,
                    { new = it },
                    stringResource(R.string.account_password_new),
                    errors.new,
                    hint = stringResource(R.string.account_password_hint),
                    enabled = !busy,
                )
                SecretField(
                    confirm,
                    { confirm = it },
                    stringResource(R.string.account_password_confirm),
                    errors.confirm,
                    enabled = !busy,
                    last = true,
                    onDone = { onSubmit(current, new, confirm) },
                )
                errors.general?.let { FormError(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onSubmit(current, new, confirm) }, enabled = !busy) {
                        Text(
                            stringResource(
                                if (busy) R.string.account_password_changing
                                else R.string.account_password_save
                            )
                        )
                    }
                    TextButton(onClick = ::close, enabled = !busy) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            }
        }
    }
}

@Composable
private fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    @androidx.annotation.StringRes error: Int?,
    modifier: Modifier = Modifier,
    hint: String? = null,
    enabled: Boolean = true,
    last: Boolean = false,
    onDone: () -> Unit = {},
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = error != null,
        supportingText =
            (error?.let { stringResource(it) } ?: hint)?.let { text -> { Text(text) } },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = if (last) ImeAction.Done else ImeAction.Next,
            ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onDone() }),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun FormError(@androidx.annotation.StringRes message: Int) {
    Text(
        stringResource(message),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** The "New token" header action; hidden while the form is open. */
@Composable
internal fun NewTokenAction(visible: Boolean, onClick: () -> Unit) {
    if (!visible) return
    TextButton(onClick = onClick) {
        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(stringResource(R.string.account_token_new), modifier = Modifier.padding(start = 6.dp))
    }
}

/** Inline "new token" form: a name, the current password (step-up) and a primary create button. */
@Composable
internal fun NewTokenForm(
    open: Boolean,
    busy: Boolean,
    passwordLogin: Boolean,
    /** A login provider the account has linked: confirms the token without a password. */
    provider: OidcProvider?,
    errors: TokenFormErrors,
    onCancel: () -> Unit,
    onCreate: (name: String, password: String) -> Unit,
    onCreateWithProvider: (name: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    AnimatedVisibility(
        visible = open,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!passwordLogin && provider == null) {
                Text(
                    stringResource(R.string.account_token_password_login_only),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
            } else {
                Text(
                    stringResource(R.string.account_token_new_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.account_token_name)) },
                    placeholder = { Text(stringResource(R.string.account_token_name_hint)) },
                    singleLine = true,
                    enabled = !busy,
                    isError = errors.name != null,
                    supportingText = errors.name?.let { { Text(stringResource(it)) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (passwordLogin) {
                    SecretField(
                        password,
                        { password = it },
                        stringResource(R.string.account_password_current),
                        errors.password,
                        enabled = !busy,
                        last = true,
                        onDone = { onCreate(name, password) },
                    )
                }
                errors.general?.let { FormError(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (passwordLogin) onCreate(name, password)
                            else onCreateWithProvider(name)
                        },
                        enabled = !busy,
                    ) {
                        Text(
                            stringResource(
                                if (busy) R.string.account_token_creating
                                else R.string.account_token_create
                            )
                        )
                    }
                    TextButton(onClick = onCancel, enabled = !busy) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
                if (passwordLogin && provider != null) {
                    TextButton(onClick = { onCreateWithProvider(name) }, enabled = !busy) {
                        Text(stringResource(R.string.oidc_reauth_with_provider, provider.name))
                    }
                }
            }
        }
    }
}

/** One token row: device chip, name (+ "This device"), created and expiry dates, revoke button. */
@Composable
internal fun TokenRow(
    token: ApiTokenDto,
    isThisDevice: Boolean,
    revoking: Boolean,
    onRevoke: () -> Unit,
) {
    SettingsListItem(
        leadingContent = {
            Box(
                Modifier.size(40.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Smartphone,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(22.dp),
                )
            }
        },
        headlineContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(token.name, modifier = Modifier.weight(1f, fill = false), maxLines = 2)
                if (isThisDevice) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) {
                        Text(
                            stringResource(R.string.account_token_this_device),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        },
        supportingContent = {
            Column {
                Text(
                    stringResource(
                        R.string.account_token_created_on,
                        formatTokenDate(token.createdAt),
                    )
                )
                Text(
                    token.lastUsedAt?.let {
                        stringResource(R.string.account_token_last_used, formatTokenDate(it))
                    } ?: stringResource(R.string.account_token_never_used)
                )
                token.expiresAt?.let {
                    Text(stringResource(R.string.account_token_expires_on, formatTokenDate(it)))
                }
            }
        },
        trailingContent = {
            if (revoking) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onRevoke) {
                    Icon(
                        PhosphorIcons.Trash,
                        contentDescription =
                            stringResource(R.string.account_token_revoke_cd, token.name),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
    )
}

/** Confirm before removing the picture. */
@Composable
internal fun RemoveAvatarDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.account_avatar_remove_title)) },
        text = { Text(stringResource(R.string.account_avatar_remove_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.account_avatar_remove)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/** Confirm a revoke; for the token this app uses the text says it signs the user out. */
@Composable
internal fun RevokeTokenDialog(
    token: ApiTokenDto,
    isThisDevice: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.account_token_revoke_title)) },
        text = {
            Text(
                if (isThisDevice) stringResource(R.string.account_token_revoke_this_device_body)
                else stringResource(R.string.account_token_revoke_body, token.name)
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.account_token_revoke_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/** Reveal-once: the secret is only here until the user closes the dialog. */
@Composable
internal fun RevealedTokenDialog(name: String, token: String, onDone: () -> Unit) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    AlertDialog(
        // No outside-tap or back dismissal: the only way out is the explicit Done.
        onDismissRequest = {},
        title = { Text(stringResource(R.string.account_token_reveal_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.account_token_reveal_body, name))
                SelectionContainer {
                    Text(
                        token,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier =
                            Modifier.fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surfaceContainerHighest,
                                    MaterialTheme.shapes.medium,
                                )
                                .padding(12.dp),
                    )
                }
                Text(
                    stringResource(R.string.account_token_reveal_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDone) { Text(stringResource(R.string.account_token_done)) }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    copySensitive(context, token)
                    copied = true
                }
            ) {
                Text(
                    stringResource(
                        if (copied) R.string.account_token_copied else R.string.account_token_copy
                    )
                )
            }
        },
    )
}

/** Explains why the app signs out now; the only way on is the button, which signs out locally. */
@Composable
internal fun SignedOutDialog(notice: SignOutNotice, onContinue: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = {
            Text(
                stringResource(
                    when (notice) {
                        SignOutNotice.PasswordChanged -> R.string.account_password_done_title
                        SignOutNotice.ThisDeviceRevoked -> R.string.account_token_self_done_title
                    }
                )
            )
        },
        text = {
            Text(
                stringResource(
                    when (notice) {
                        SignOutNotice.PasswordChanged -> R.string.account_password_done_body
                        SignOutNotice.ThisDeviceRevoked -> R.string.account_token_self_done_body
                    }
                )
            )
        },
        confirmButton = {
            TextButton(onClick = onContinue) {
                Text(stringResource(R.string.account_signed_out_continue))
            }
        },
    )
}

private fun copySensitive(context: Context, secret: String) {
    val clipboard =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    val clip = ClipData.newPlainText("token", secret)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras =
            PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    clipboard.setPrimaryClip(clip)
}

@Composable internal fun SectionDivider() = HorizontalDivider(Modifier.padding(horizontal = 16.dp))
