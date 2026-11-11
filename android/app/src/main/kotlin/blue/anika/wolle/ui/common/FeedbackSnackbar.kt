package blue.anika.wolle.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import blue.anika.wolle.R

/** What a feedback message is about; decides the badge icon, its tint and how long it stays. */
enum class FeedbackKind {
    Success,
    Info,
    Error,
    Offline,
}

/**
 * How long a message of [kind] stays. Success, info and offline notes ("Project created." parked
 * for the next sync) are confirmations, not problems: they leave on their own, after the short
 * standard time, or the long one when there is an action to reach (Undo). Only errors stay until
 * dismissed, so a failure can not be missed. The host scales finite durations for accessibility
 * (TalkBack timeouts), so none of them vanishes before it can be read out.
 */
internal fun feedbackDuration(kind: FeedbackKind, hasAction: Boolean): SnackbarDuration =
    when (kind) {
        FeedbackKind.Error -> SnackbarDuration.Indefinite
        FeedbackKind.Success,
        FeedbackKind.Info,
        FeedbackKind.Offline ->
            if (hasAction || kind == FeedbackKind.Offline) SnackbarDuration.Long
            else SnackbarDuration.Short
    }

/** [SnackbarVisuals] that carry a [kind]; see [feedbackDuration] for how long they stay. */
class FeedbackVisuals(
    override val message: String,
    val kind: FeedbackKind = FeedbackKind.Success,
    override val actionLabel: String? = null,
    override val duration: SnackbarDuration = feedbackDuration(kind, actionLabel != null),
) : SnackbarVisuals {
    override val withDismissAction: Boolean
        get() = kind == FeedbackKind.Error || kind == FeedbackKind.Offline
}

suspend fun SnackbarHostState.showFeedback(
    message: String,
    kind: FeedbackKind = FeedbackKind.Success,
    actionLabel: String? = null,
): SnackbarResult = showSnackbar(FeedbackVisuals(message, kind, actionLabel))

/** Replaces the stock [SnackbarHost] so every message shares the pill design of the website. */
@Composable
fun StricknaniSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState, modifier = modifier) { data -> FeedbackSnackbar(data) }
}

@Composable
private fun FeedbackSnackbar(data: SnackbarData) {
    val visuals = data.visuals
    val kind = (visuals as? FeedbackVisuals)?.kind ?: FeedbackKind.Info
    val scheme = MaterialTheme.colorScheme
    val badgeContainer: Color
    val badgeTint: Color
    when (kind) {
        FeedbackKind.Success -> {
            badgeContainer = scheme.secondaryContainer
            badgeTint = scheme.onSecondaryContainer
        }
        FeedbackKind.Info -> {
            badgeContainer = scheme.primaryContainer
            badgeTint = scheme.onPrimaryContainer
        }
        FeedbackKind.Error -> {
            badgeContainer = scheme.error
            badgeTint = scheme.onError
        }
        FeedbackKind.Offline -> {
            badgeContainer = scheme.tertiaryContainer
            badgeTint = scheme.onTertiaryContainer
        }
    }
    val icon =
        when (kind) {
            FeedbackKind.Success -> FeedbackIcons.Check
            FeedbackKind.Info -> PhosphorIcons.Info
            FeedbackKind.Error -> FeedbackIcons.Exclamation
            FeedbackKind.Offline -> PhosphorIcons.CloudSlash
        }

    // A gentle spring up from below; users who switched animations off get no motion at all. The
    // host fades the snackbar out on its own.
    val still = animationsDisabled()
    val enter = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!still) {
            enter.animateTo(
                1f,
                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 380f),
            )
        }
    }

    Surface(
        modifier =
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp).graphicsLayer {
                val progress = enter.value
                translationY = (1f - progress) * 24.dp.toPx()
                alpha = progress.coerceIn(0f, 1f)
            },
        shape = RoundedCornerShape(28.dp),
        color = scheme.surfaceContainerHigh,
        contentColor = scheme.onSurface,
        shadowElevation = 6.dp,
        border =
            BorderStroke(
                1.dp,
                if (kind == FeedbackKind.Error) scheme.error else scheme.outlineVariant,
            ),
    ) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .padding(
                        start = 10.dp,
                        end = if (visuals.withDismissAction) 4.dp else 14.dp,
                        top = 4.dp,
                        bottom = 4.dp,
                    ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(color = badgeContainer, shape = CircleShape, modifier = Modifier.size(32.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = badgeTint,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Text(
                visuals.message,
                modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            visuals.actionLabel?.let { label ->
                TextButton(onClick = { data.performAction() }) {
                    Text(label, fontWeight = FontWeight.Bold)
                }
            }
            if (visuals.withDismissAction) {
                IconButton(onClick = { data.dismiss() }, modifier = Modifier.size(44.dp)) {
                    Icon(
                        FeedbackIcons.Close,
                        contentDescription = stringResource(R.string.feedback_dismiss),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/** Plain stroked glyphs for the badge and close button, matching the website's `NavIcon`. */
internal object FeedbackIcons {
    private fun stroked(
        name: String,
        draw: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit,
    ) =
        ImageVector.Builder(
                name = name,
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            )
            .path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round,
                pathBuilder = draw,
            )
            .build()

    val Check: ImageVector by lazy {
        stroked("FeedbackCheck") {
            moveTo(4.5f, 12.5f)
            lineToRelative(5f, 5f)
            lineToRelative(10f, -11f)
        }
    }

    val Close: ImageVector by lazy {
        stroked("FeedbackClose") {
            moveTo(6f, 6f)
            lineTo(18f, 18f)
            moveTo(18f, 6f)
            lineTo(6f, 18f)
        }
    }

    val Exclamation: ImageVector by lazy {
        stroked("FeedbackExclamation") {
            moveTo(12f, 6.5f)
            lineTo(12f, 13.5f)
            moveTo(12f, 17.2f)
            lineTo(12f, 17.3f)
        }
    }
}
