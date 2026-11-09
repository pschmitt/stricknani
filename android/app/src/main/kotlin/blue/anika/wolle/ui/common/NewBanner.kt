package blue.anika.wolle.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

/** Which page the [NewBanner] welcomes people to. */
enum class NewBannerKind {
    Project,
    Yarn,
}

/**
 * The welcome band of the New project and New yarn screens: the mascot doing what the page is about
 * (knitting, or playing with yarn), a warm line and a hint. Same composition and copy as the web
 * app's `NewBanner.svelte`; the art is drawn on a 128 x 104 grid.
 */
@Composable
fun NewBanner(
    kind: NewBannerKind,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val tone = if (kind == NewBannerKind.Project) scheme.tertiary else scheme.secondary
    val wash =
        if (kind == NewBannerKind.Project) scheme.tertiaryContainer else scheme.secondaryContainer
    val paper = scheme.surface
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(max = 120.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(wash)
                .padding(end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(width = 128.dp, height = 104.dp).clearAndSetSemantics {}) {
            Canvas(Modifier.size(width = 128.dp, height = 104.dp)) {
                drawEllipse(tone.copy(alpha = 0.18f), 24f, 97f, 80f, 6f)
            }
            Mascot(
                size = 76.dp,
                modifier = Modifier.offset(x = 26.dp, y = 4.dp),
                bob = true,
                reactive = false,
                exposeSemantics = false,
            )
            Canvas(Modifier.size(width = 128.dp, height = 104.dp)) {
                if (kind == NewBannerKind.Project) drawKnitting(tone, paper)
                else drawYarn(tone, paper)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, color = tone)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

private fun DrawScope.drawEllipse(color: Color, x: Float, y: Float, w: Float, h: Float) {
    drawOval(color, Offset(x.dp.toPx(), y.dp.toPx()), Size(w.dp.toPx(), h.dp.toPx()))
}

private fun DrawScope.drawKnitting(tone: Color, paper: Color) {
    fun p(v: Float) = v.dp.toPx()
    // The knitted piece with its rows of v-stitches.
    drawRoundRect(paper, Offset(p(42f), p(80f)), Size(p(44f), p(20f)), CornerRadius(p(4f)))
    val line = Stroke(p(1.5f), cap = StrokeCap.Round, join = StrokeJoin.Round)
    drawRoundRect(tone, Offset(p(42f), p(80f)), Size(p(44f), p(20f)), CornerRadius(p(4f)), line)
    val stitches = Path()
    for ((y, count) in listOf(86f to 4, 92f to 4)) {
        for (i in 0 until count) {
            val x = 48f + i * 10f
            stitches.moveTo(p(x), p(y))
            stitches.lineTo(p(x + 3f), p(y + 3f))
            stitches.lineTo(p(x + 6f), p(y))
        }
    }
    drawPath(stitches, tone, style = line)
    // The two needles, crossed, with a knob on each upper end.
    drawLine(tone, Offset(p(26f), p(100f)), Offset(p(92f), p(62f)), p(4f), StrokeCap.Round)
    drawLine(tone, Offset(p(102f), p(100f)), Offset(p(36f), p(62f)), p(4f), StrokeCap.Round)
    drawCircle(tone, p(3.6f), Offset(p(92f), p(62f)))
    drawCircle(tone, p(3.6f), Offset(p(36f), p(62f)))
}

private fun DrawScope.drawYarn(tone: Color, paper: Color) {
    fun p(v: Float) = v.dp.toPx()
    // The loose thread trailing off to the right.
    val thread = Path()
    thread.moveTo(p(96f), p(90f))
    thread.cubicTo(p(112f), p(96f), p(100f), p(102f), p(112f), p(102f))
    thread.cubicTo(p(120f), p(102f), p(126f), p(94f), p(124f), p(90f))
    drawPath(thread, tone, style = Stroke(p(2.4f), cap = StrokeCap.Round, join = StrokeJoin.Round))
    // The ball of yarn with three wraps.
    drawCircle(paper, p(16f), Offset(p(88f), p(78f)))
    drawCircle(tone, p(16f), Offset(p(88f), p(78f)), style = Stroke(p(2f)))
    val wraps = Path()
    wraps.moveTo(p(74f), p(72f))
    wraps.quadraticTo(p(88f), p(64f), p(102f), p(74f))
    wraps.moveTo(p(73f), p(80f))
    wraps.quadraticTo(p(88f), p(74f), p(103f), p(82f))
    wraps.moveTo(p(78f), p(90f))
    wraps.quadraticTo(p(88f), p(84f), p(98f), p(90f))
    drawPath(wraps, tone, style = Stroke(p(1.6f), cap = StrokeCap.Round))
}
