package blue.anika.wolle.ui.signin

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import blue.anika.wolle.R
import blue.anika.wolle.ui.common.PhosphorIcons
import blue.anika.wolle.ui.common.animationsDisabled
import blue.anika.wolle.ui.home.SYNC_SHAKE_ANGLES

/**
 * The big "you have been signed out" panel at the very top of Home. Shown for as long as the server
 * rejects the app's token; it can not be dismissed, only resolved by signing in again. Changes made
 * on this phone are kept and uploaded afterwards ([pendingChangesCount] says how many wait).
 */
@Composable
fun SignedOutPanel(
    pendingChangesCount: Int,
    working: Boolean,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.errorContainer,
        contentColor = colors.onErrorContainer,
        shape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp),
        modifier = modifier.fillMaxWidth().testTag("signed-out-panel"),
    ) {
        Column(
            modifier =
                Modifier.windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ShakingLockBadge()
            Text(
                stringResource(R.string.signed_out_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.signed_out_text),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (pendingChangesCount > 0) {
                Text(
                    pluralStringResource(
                        R.plurals.signed_out_waiting,
                        pendingChangesCount,
                        pendingChangesCount,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.testTag("signed-out-waiting"),
                )
            }
            Button(
                onClick = onSignIn,
                enabled = !working,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = colors.error,
                        contentColor = colors.onError,
                    ),
                modifier =
                    Modifier.fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .padding(top = 4.dp)
                        .testTag("signed-out-sign-in"),
            ) {
                if (working) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp).padding(end = 0.dp),
                        strokeWidth = 2.dp,
                        color = colors.onError,
                    )
                }
                Text(
                    stringResource(R.string.signed_out_button),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = if (working) 8.dp else 0.dp),
                )
            }
        }
    }
}

/** A big lock in a round badge that shakes its head once when the panel appears. */
@Composable
private fun ShakingLockBadge() {
    val still = animationsDisabled()
    val wiggle = remember { Animatable(0f) }
    LaunchedEffect(still) {
        if (still) return@LaunchedEffect
        for (angle in SYNC_SHAKE_ANGLES) wiggle.animateTo(angle, tween(90))
    }
    Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
        Surface(
            color = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
            shape = CircleShape,
            modifier = Modifier.size(72.dp).graphicsLayer { rotationZ = wiggle.value },
        ) {
            Icon(
                PhosphorIcons.LockKey,
                contentDescription = null,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

/**
 * A slim banner at the top of every other screen while signed out. Sits in the error colours and
 * stays until the user signs in again.
 */
@Composable
fun SignedOutBanner(working: Boolean, onSignIn: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(colors.errorContainer)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 16.dp, end = 8.dp, top = 2.dp, bottom = 2.dp)
                .testTag("signed-out-banner"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            PhosphorIcons.LockKey,
            contentDescription = null,
            tint = colors.onErrorContainer,
            modifier = Modifier.size(20.dp).clip(CircleShape),
        )
        Text(
            stringResource(R.string.signed_out_banner),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onErrorContainer,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = onSignIn,
            enabled = !working,
            modifier = Modifier.testTag("signed-out-banner-sign-in"),
        ) {
            Text(
                stringResource(R.string.signed_out_banner_action),
                color = colors.error,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
