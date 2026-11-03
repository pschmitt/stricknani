package blue.anika.wolle.ui.admin

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import blue.anika.wolle.R
import blue.anika.wolle.data.repository.AdminFailure
import blue.anika.wolle.ui.common.PhosphorIcons
import coil3.compose.SubcomposeAsyncImage

@StringRes
fun AdminFailure.messageRes(): Int =
    when (this) {
        AdminFailure.Offline -> R.string.admin_error_offline
        AdminFailure.NotAllowed -> R.string.admin_error_not_allowed
        AdminFailure.LastAdmin -> R.string.admin_error_last_admin
        AdminFailure.EmailInUse -> R.string.admin_error_email_in_use
        AdminFailure.InvalidEmail -> R.string.admin_error_invalid_email
        AdminFailure.PasswordTooShort -> R.string.admin_error_password_short
        AdminFailure.PasswordTooCommon -> R.string.admin_error_password_common
        AdminFailure.PasswordTooLong -> R.string.admin_error_password_long
        AdminFailure.NotFound -> R.string.admin_error_not_found
        AdminFailure.Other -> R.string.admin_error_other
    }

fun AdminFailure.icon(): ImageVector =
    when (this) {
        AdminFailure.Offline -> PhosphorIcons.CloudSlash
        AdminFailure.NotAllowed -> PhosphorIcons.LockKey
        else -> PhosphorIcons.SmileySad
    }

/** The colour an [AuditTone] resolves to in the current (light or dark) theme. */
@Composable
fun AuditTone.color(): Color {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    return when (this) {
        AuditTone.Green -> if (dark) AddedGreenDark else AddedGreenLight
        AuditTone.Blue -> if (dark) UpdateBlueDark else UpdateBlueLight
        AuditTone.Red -> scheme.error
        AuditTone.Marigold -> scheme.tertiary
        AuditTone.Muted -> scheme.onSurfaceVariant
    }
}

/**
 * A friendly full-area message with a retry button, for a screen that could not load: offline, no
 * permission or a server hiccup.
 */
@Composable
fun AdminFailureState(failure: AdminFailure, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            failure.icon(),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp),
        )
        Text(
            stringResource(failure.messageRes()),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
        if (failure != AdminFailure.NotAllowed) {
            Button(onClick = onRetry, modifier = Modifier.padding(top = 20.dp)) {
                Text(stringResource(R.string.admin_try_again))
            }
        }
    }
}

/**
 * A round avatar: the thumbnail when there is one and it loads, otherwise the first letter of the
 * email on a soft tint. [dimmed] fades it for disabled accounts.
 */
@Composable
fun AdminAvatar(
    url: String?,
    email: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
) {
    val fallback: @Composable () -> Unit = {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(size).background(MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Text(
                initialOf(email),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                style = MaterialTheme.typography.labelLarge,
                fontSize = (size.value * 0.42f).sp,
            )
        }
    }
    Box(
        modifier = modifier.size(size).clip(CircleShape).alpha(if (dimmed) 0.5f else 1f),
        contentAlignment = Alignment.Center,
    ) {
        if (url == null) {
            fallback()
        } else {
            SubcomposeAsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
                loading = { fallback() },
                error = { fallback() },
            )
        }
    }
}
