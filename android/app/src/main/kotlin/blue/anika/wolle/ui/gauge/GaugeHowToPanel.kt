package blue.anika.wolle.ui.gauge

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import blue.anika.wolle.R

/**
 * The web's collapsible "How to use this calculator" panel (closed by default) with its four steps.
 */
@Composable
internal fun GaugeHowToPanel() {
    var open by rememberSaveable { mutableStateOf(false) }
    val steps =
        listOf(
            R.string.gauge_calculator_how_to_step_1,
            R.string.gauge_calculator_how_to_step_2,
            R.string.gauge_calculator_how_to_step_3,
            R.string.gauge_calculator_how_to_step_4,
        )
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().testTag("gauge-how-to"),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier.fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button) { open = !open }
                        .padding(horizontal = 18.dp, vertical = 8.dp),
            ) {
                Text(
                    stringResource(R.string.gauge_calculator_how_to_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                )
            }
            AnimatedVisibility(visible = open) {
                Column(
                    Modifier.padding(start = 18.dp, end = 18.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    steps.forEachIndexed { index, step ->
                        Text(
                            "${index + 1}. ${stringResource(step)}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}
