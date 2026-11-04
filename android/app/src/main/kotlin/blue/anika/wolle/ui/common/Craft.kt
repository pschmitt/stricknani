package blue.anika.wolle.ui.common

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import blue.anika.wolle.R
import blue.anika.wolle.ui.common.mascot.MascotCanvas
import blue.anika.wolle.ui.common.mascot.MascotCue
import blue.anika.wolle.ui.common.mascot.MascotMood

/**
 * A dashed "running stitch" just inside the edge, like a patch sewn onto fabric - the app's
 * signature detail. Use it sparingly (hero cards, the onboarding card), not on every surface.
 */
fun Modifier.stitchedBorder(
    color: Color,
    inset: Dp = 7.dp,
    cornerRadius: Dp = 21.dp,
    stitch: Dp = 7.dp,
    gap: Dp = 5.dp,
    width: Dp = 1.6.dp,
): Modifier = drawWithContent {
    drawContent()
    val insetPx = inset.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(insetPx, insetPx),
        size = Size(size.width - 2 * insetPx, size.height - 2 * insetPx),
        cornerRadius = CornerRadius(cornerRadius.toPx()),
        style =
            Stroke(
                width = width.toPx(),
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(stitch.toPx(), gap.toPx())),
            ),
    )
}

/** True when the user turned animations off (Developer options or Accessibility). */
@Composable
fun animationsDisabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/**
 * The yarn-ball mascot ("Winky Pom"). The ball is about 60% of [size] (the launcher geometry), so
 * existing layouts keep their size. [bob] adds a slow, gentle sway. With [reactive] it answers
 * taps, drags and flicks and fidgets while idle; [exposeSemantics] = false keeps it out of the
 * accessibility tree and focus order, for a mascot inside another clickable (a header that
 * navigates): the reaction still plays and the parent still gets the click. [onReact] runs on every
 * tap on the ball.
 */
@Composable
fun Mascot(
    size: Dp,
    modifier: Modifier = Modifier,
    bob: Boolean = false,
    reactive: Boolean = true,
    exposeSemantics: Boolean = true,
    onReact: (() -> Unit)? = null,
    // Loops a gentle "working" roll while true.
    busy: Boolean = false,
    // A one-off emotion; each new instance plays once.
    cue: MascotCue? = null,
    // A lasting expression (error screens); Happy is the normal face.
    mood: MascotMood = MascotMood.Happy,
) {
    val reduced = animationsDisabled()
    val transition = rememberInfiniteTransition(label = "mascot")
    val sway by
        transition.animateFloat(
            initialValue = -6f,
            targetValue = 6f,
            animationSpec = infiniteRepeatable(tween(1800), RepeatMode.Reverse),
            label = "mascot-sway",
        )
    MascotCanvas(
        size = size,
        modifier = modifier,
        bob = bob,
        reactive = reactive,
        exposeSemantics = exposeSemantics,
        onReact = onReact,
        reduced = reduced,
        sway = { sway },
        busy = busy,
        cue = cue,
        mood = mood,
    )
}

/** Loading indicator: the mascot rolling like a ball of yarn. */
@Composable
fun YarnLoader(modifier: Modifier = Modifier, size: Dp = 72.dp) {
    val still = animationsDisabled()
    val transition = rememberInfiniteTransition(label = "yarn-loader")
    val turn by
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
            label = "yarn-loader-turn",
        )
    Image(
        painter = painterResource(R.drawable.ic_launcher_foreground),
        contentDescription = null,
        modifier = modifier.size(size).graphicsLayer { if (!still) rotationZ = turn },
    )
}
