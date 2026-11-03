package blue.anika.wolle.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import blue.anika.wolle.R
import kotlin.math.roundToInt

/**
 * The reading text size of the project and yarn detail screens: three steps (90%, 100%, 115%), the
 * same as the web project reader. The chosen step is stored in the app preferences.
 */
object ReadingTextSize {
    val factors: List<Float> = listOf(0.9f, 1.0f, 1.15f)
    const val DEFAULT_STEP = 1

    fun clamp(step: Int): Int = step.coerceIn(0, factors.lastIndex)

    /** [current] moved by [delta] steps, never leaving the available range. */
    fun stepped(current: Int, delta: Int): Int = clamp(clamp(current) + delta)

    fun canDecrease(step: Int): Boolean = clamp(step) > 0

    fun canIncrease(step: Int): Boolean = clamp(step) < factors.lastIndex

    fun isDefault(step: Int): Boolean = clamp(step) == DEFAULT_STEP

    fun factor(step: Int): Float = factors[clamp(step)]

    fun percentLabel(step: Int): String = "${(factor(step) * 100).roundToInt()}%"
}

/**
 * Scales the text of [content] (everything in `sp`) by the chosen step, leaving layout in `dp` and
 * everything outside, such as the top bar and menus, alone.
 */
@Composable
fun ReadingTextScale(step: Int, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val scaled = Density(density.density, density.fontScale * ReadingTextSize.factor(step))
    CompositionLocalProvider(LocalDensity provides scaled, content = content)
}

/**
 * The "Text size" part of a detail screen's overflow menu: a labelled `A− | 100% | A+` control and,
 * when the size is not the default, a "Reset text size" item. None of the controls close the menu,
 * so the size can be stepped repeatedly while the page behind updates.
 */
@Composable
fun TextSizeMenuSection(step: Int, onStepChange: (Int) -> Unit) {
    val smaller = stringResource(R.string.text_size_smaller)
    val larger = stringResource(R.string.text_size_larger)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            stringResource(R.string.text_size_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp).semantics { heading() },
        )
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { onStepChange(ReadingTextSize.stepped(step, -1)) },
                    enabled = ReadingTextSize.canDecrease(step),
                    modifier =
                        Modifier.heightIn(min = 48.dp).widthIn(min = 64.dp).semantics {
                            contentDescription = smaller
                        },
                ) {
                    Text("A−", style = MaterialTheme.typography.titleMedium)
                }
                Box(Modifier.widthIn(min = 56.dp), contentAlignment = Alignment.Center) {
                    Text(
                        ReadingTextSize.percentLabel(step),
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center,
                    )
                }
                TextButton(
                    onClick = { onStepChange(ReadingTextSize.stepped(step, +1)) },
                    enabled = ReadingTextSize.canIncrease(step),
                    modifier =
                        Modifier.heightIn(min = 48.dp).widthIn(min = 64.dp).semantics {
                            contentDescription = larger
                        },
                ) {
                    Text("A+", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
    if (!ReadingTextSize.isDefault(step)) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.text_size_reset)) },
            onClick = { onStepChange(ReadingTextSize.DEFAULT_STEP) },
        )
    }
}
