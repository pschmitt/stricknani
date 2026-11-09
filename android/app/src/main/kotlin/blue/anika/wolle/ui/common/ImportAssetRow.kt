package blue.anika.wolle.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import blue.anika.wolle.R
import blue.anika.wolle.data.api.dto.ImportAssetDto

/** One diagram or PDF of the imported page with its kind, label and a checkbox. */
@Composable
fun ImportAssetRow(asset: ImportAssetDto, selected: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).testTag("import-asset"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = { onToggle() })
        Column {
            Text(
                text =
                    stringResource(
                        if (asset.isPdf) R.string.import_asset_kind_pdf
                        else R.string.import_asset_kind_diagram
                    ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = asset.label?.takeIf { it.isNotBlank() } ?: asset.url.substringAfterLast('/'),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
