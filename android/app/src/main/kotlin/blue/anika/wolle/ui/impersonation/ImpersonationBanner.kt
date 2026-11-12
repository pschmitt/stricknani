package blue.anika.wolle.ui.impersonation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import blue.anika.wolle.R
import blue.anika.wolle.data.impersonation.ImpersonationSession
import kotlinx.coroutines.delay

private val BannerBackground = Color(0xFF8A1C14)
private val BannerStripe = Color(0xFFFFD54A)

/**
 * Shown above every screen while an administrator acts as another account, with the web's wording
 * and colours (`ImpersonationBanner.svelte`). It cannot be dismissed: the only way out is Stop,
 * which returns to the administrator's own account list. It also stops by itself when the server's
 * expiry passes or the server no longer accepts the credential.
 */
@Composable
fun ImpersonationBanner(
    session: ImpersonationSession,
    credentialRejected: Boolean,
    nowSeconds: () -> Long,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var stopping by remember { mutableStateOf(false) }
    val stop = {
        if (!stopping) {
            stopping = true
            onStop()
        }
    }
    LaunchedEffect(credentialRejected) { if (credentialRejected) stop() }
    LaunchedEffect(session.expiresAtSeconds) {
        delay(session.secondsLeft(nowSeconds()) * MILLIS_PER_SECOND)
        stop()
    }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag("impersonation-banner")
                .semantics { liveRegion = LiveRegionMode.Polite }
                .background(BannerBackground)
                .drawBehind {
                    val stroke = STRIPE_HEIGHT.toPx()
                    drawLine(
                        color = BannerStripe,
                        start = Offset(0f, size.height - stroke / 2),
                        end = Offset(size.width, size.height - stroke / 2),
                        strokeWidth = stroke,
                    )
                }
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp + STRIPE_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.impersonation_banner_title, session.targetEmail),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.impersonation_banner_notice),
                color = Color.White.copy(alpha = 0.9f),
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            )
        }
        Button(
            onClick = stop,
            enabled = !stopping,
            colors =
                ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = BannerBackground),
            border = BorderStroke(1.dp, BannerStripe),
            modifier = Modifier.testTag("impersonation-stop"),
        ) {
            Text(stringResource(R.string.impersonation_banner_stop))
        }
    }
}

private val STRIPE_HEIGHT = 4.dp
private const val MILLIS_PER_SECOND = 1000L
