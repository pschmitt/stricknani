package blue.anika.wolle.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import blue.anika.wolle.R
import blue.anika.wolle.data.export.LocalMoveOffer

/**
 * T130.2: after the sign-in that followed "Use a server instead", offers to upload the projects
 * kept on this phone. Upload starts the restore of the exported file; Not now keeps the offer;
 * Discard deletes the file.
 */
@Composable
fun LocalMoveCard(
    offer: LocalMoveOffer,
    onUpload: (LocalMoveOffer.Ask) -> Unit,
    onNotNow: (LocalMoveOffer.Ask) -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth().testTag("home-local-move-card")) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (offer) {
                is LocalMoveOffer.Ask -> {
                    Text(
                        stringResource(R.string.local_move_title, offer.host),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.local_move_text),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onUpload(offer) },
                            modifier = Modifier.testTag("local-move-upload"),
                        ) {
                            Text(stringResource(R.string.local_move_upload))
                        }
                        TextButton(
                            onClick = { onNotNow(offer) },
                            modifier = Modifier.testTag("local-move-not-now"),
                        ) {
                            Text(stringResource(R.string.local_move_not_now))
                        }
                        TextButton(
                            onClick = onDiscard,
                            modifier = Modifier.testTag("local-move-discard"),
                        ) {
                            Text(stringResource(R.string.local_move_discard))
                        }
                    }
                }
                is LocalMoveOffer.Uploading ->
                    Text(
                        stringResource(R.string.local_move_uploading, offer.host),
                        style = MaterialTheme.typography.titleMedium,
                    )
            }
        }
    }
}
