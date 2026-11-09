package blue.anika.wolle.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** One choice of a [SegmentedControl]. [contentDescription] replaces the label for TalkBack. */
data class SegmentedOption<T>(
    val value: T,
    val label: String,
    val icon: ImageVector? = null,
    val enabled: Boolean = true,
    val contentDescription: String? = null,
    val testTag: String? = null,
)

/** Where the thumb rests: the selected option, or the first one when none matches. */
internal fun segmentThumbIndex(count: Int, selectedIndex: Int): Int =
    if (count <= 0) 0 else selectedIndex.coerceIn(0, count - 1)

/** Whether a thumb is drawn at all: only when the selected value is one of the options. */
internal fun segmentHasThumb(count: Int, selectedIndex: Int): Boolean =
    selectedIndex in 0 until count

private val ThumbEasing = CubicBezierEasing(0.34f, 1.4f, 0.64f, 1f)
private const val ThumbMillis = 210

/**
 * The app's one single-choice toggle, the same as the website's SegmentedControl: a soft rounded
 * track with a filled primary thumb that slides to the chosen option (a short springy ease, no
 * motion when animations are off) and a gentle shadow under it. A radio group for TalkBack:
 * [Role.RadioButton] on each option inside a [selectableGroup] described by [label].
 *
 * Options share the width equally. With [fullWidth] the control fills the available width,
 * otherwise it is as wide as its widest option times the count. [compact] is the 36dp variant; the
 * regular one is 44dp tall.
 */
@Composable
fun <T> SegmentedControl(
    options: List<SegmentedOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    fullWidth: Boolean = false,
    compact: Boolean = false,
    enabled: Boolean = true,
) {
    val selectedIndex = options.indexOfFirst { it.value == selected }
    val count = options.size
    val pad = if (compact) 3.dp else 4.dp
    val optionHeight = if (compact) 36.dp else 44.dp
    val reduceMotion = animationsDisabled()
    val position by
        animateFloatAsState(
            targetValue = segmentThumbIndex(count, selectedIndex).toFloat(),
            animationSpec = if (reduceMotion) snap() else tween(ThumbMillis, easing = ThumbEasing),
            label = "segmented-thumb",
        )
    val widthModifier =
        if (fullWidth) Modifier.fillMaxWidth() else Modifier.width(IntrinsicSize.Max)

    Box(
        modifier =
            modifier
                .then(widthModifier)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                .padding(pad)
                .semantics { contentDescription = label }
                .selectableGroup()
    ) {
        if (count > 0 && segmentHasThumb(count, selectedIndex)) {
            Box(
                Modifier.fillMaxWidth(1f / count)
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, placeable.height) {
                            placeable.place((placeable.width * position).roundToInt(), 0)
                        }
                    }
                    .height(optionHeight)
                    .alpha(if (enabled) 1f else 0.5f)
                    .shadow(3.dp, CircleShape)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            )
        }
        Row(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                val optionEnabled = enabled && option.enabled
                val on = index == selectedIndex
                val color by
                    animateColorAsState(
                        targetValue =
                            if (on) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        animationSpec = if (reduceMotion) snap() else tween(ThumbMillis / 2),
                        label = "segmented-label",
                    )
                Box(
                    Modifier.weight(1f)
                        .height(optionHeight)
                        .then(
                            if (option.testTag != null) Modifier.testTag(option.testTag)
                            else Modifier
                        )
                        .semantics {
                            option.contentDescription?.let { contentDescription = it }
                        }
                        .selectable(
                            selected = on,
                            enabled = optionEnabled,
                            role = Role.RadioButton,
                            onClick = { if (!on) onSelect(option.value) },
                        )
                        .alpha(if (optionEnabled) 1f else 0.5f)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement =
                            Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    ) {
                        option.icon?.let {
                            Icon(
                                it,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = color,
                            )
                        }
                        Text(
                            option.label,
                            color = color,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
