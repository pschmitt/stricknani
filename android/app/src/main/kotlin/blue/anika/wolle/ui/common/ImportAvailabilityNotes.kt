package blue.anika.wolle.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import blue.anika.wolle.R
import blue.anika.wolle.data.api.ImportAvailability

/**
 * The reasons an import option is greyed out: link imports off (the field and the button are
 * disabled by the dialog) and AI off (the import still runs, without AI help). Shows nothing when
 * everything is available.
 */
@Composable
fun ImportAvailabilityNotes(availability: ImportAvailability) {
    if (availability.url && availability.ai) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!availability.url) {
            Text(
                text = stringResource(R.string.import_unavailable_url),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("import-unavailable-url"),
            )
        }
        if (!availability.ai) {
            Text(
                text = stringResource(R.string.import_unavailable_ai),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.alpha(0.7f).testTag("import-unavailable-ai"),
            )
        }
    }
}
