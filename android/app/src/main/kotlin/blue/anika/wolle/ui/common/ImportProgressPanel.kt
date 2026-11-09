package blue.anika.wolle.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import blue.anika.wolle.R
import kotlinx.coroutines.delay

/** One step of a link import, current from [startsAtMillis] on (see [importSteps]). */
enum class ImportStep {
    Fetch,
    Pictures,
    Read,
    Review,
}

/**
 * The steps of a link import with their typical start times. The server reports no progress, so the
 * panel follows these (an AI import takes about a minute, a plain one seconds); the last step stays
 * current until the result arrives. The web's `import-progress.ts` uses the same timings.
 */
fun importSteps(ai: Boolean): List<Pair<ImportStep, Long>> =
    listOf(
        ImportStep.Fetch to 0L,
        ImportStep.Pictures to if (ai) 5_000L else 1_500L,
        ImportStep.Read to if (ai) 12_000L else 3_000L,
        ImportStep.Review to if (ai) 45_000L else 6_000L,
    )

/** The index of the current step after [elapsedMillis]. */
fun currentImportStep(steps: List<Pair<ImportStep, Long>>, elapsedMillis: Long): Int =
    steps.indexOfLast { (_, startsAt) -> elapsedMillis >= startsAt }.coerceAtLeast(0)

/** The bar's fill (0..0.95): fast at first, then slower, never full before the end. */
fun importProgressFraction(elapsedMillis: Long, ai: Boolean): Float {
    val tau = if (ai) 30_000.0 else 4_000.0
    return (0.95 * (1 - kotlin.math.exp(-elapsedMillis.coerceAtLeast(0) / tau))).toFloat()
}

/** "0:07", "1:23". */
fun formatImportElapsed(elapsedMillis: Long): String {
    val seconds = elapsedMillis.coerceAtLeast(0) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/**
 * What the import dialogs show while the server reads a link: the mascot at work, the steps, a bar
 * and the time so far, like the web's import page. [extra] adds lines below (the background hint).
 */
@Composable
fun ImportProgressPanel(
    ai: Boolean,
    modifier: Modifier = Modifier,
    extra: @Composable () -> Unit = {},
) {
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val started = System.currentTimeMillis()
        while (true) {
            elapsed = System.currentTimeMillis() - started
            delay(250)
        }
    }
    val steps = remember(ai) { importSteps(ai) }
    val current = currentImportStep(steps, elapsed)
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Mascot(size = 56.dp, busy = true, reactive = false, exposeSemantics = false)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            steps.forEachIndexed { index, (step, _) ->
                ImportStepRow(
                    label = stepLabel(step, ai),
                    done = index < current,
                    current = index == current,
                )
            }
            LinearProgressIndicator(
                progress = { importProgressFraction(elapsed, ai) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = formatImportElapsed(elapsed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (ai) {
                Text(
                    text = stringResource(R.string.import_progress_slow_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            extra()
        }
    }
}

@Composable
private fun stepLabel(step: ImportStep, ai: Boolean): String =
    stringResource(
        when (step) {
            ImportStep.Fetch -> R.string.import_step_fetch
            ImportStep.Pictures -> R.string.import_step_pictures
            ImportStep.Read -> if (ai) R.string.import_step_read_ai else R.string.import_step_read
            ImportStep.Review -> R.string.import_step_review
        }
    )

@Composable
private fun ImportStepRow(label: String, done: Boolean, current: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier.size(18.dp)
                    .then(
                        if (done) Modifier.background(accent, CircleShape)
                        else
                            Modifier.border(
                                2.dp,
                                if (current) accent else MaterialTheme.colorScheme.outlineVariant,
                                CircleShape,
                            )
                    ),
            contentAlignment = Alignment.Center,
        ) {
            if (done) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
            color =
                if (done || current) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
