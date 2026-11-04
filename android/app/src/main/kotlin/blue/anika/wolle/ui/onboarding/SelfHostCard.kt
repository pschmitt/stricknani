package blue.anika.wolle.ui.onboarding

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import blue.anika.wolle.R
import blue.anika.wolle.ui.common.Mascot
import blue.anika.wolle.ui.common.stitchedBorder

/** The public home of Stricknani, where the install docs live. */
const val SELF_HOST_REPOSITORY_URL = "https://github.com/pschmitt/stricknani"

/**
 * Shown on the sign-in screen when the server says registrations are closed: a pointer to the
 * repository and a ready-made prompt for Claude Code, Codex or opencode that sets up a private
 * instance. The same card and prompt as the website's sign-in page.
 */
@Composable
fun SelfHostCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var promptShown by remember { mutableStateOf(false) }
    val prompt = stringResource(R.string.setup_prompt)
    val copied = stringResource(R.string.selfhost_card_copied)

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
        modifier =
            modifier
                .fillMaxWidth()
                .stitchedBorder(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                    cornerRadius = 16.dp,
                )
                .testTag("selfhost-card"),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Mascot(size = 44.dp)
                Text(
                    text = stringResource(R.string.selfhost_card_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                TextButton(onClick = { uriHandler.openUri(SELF_HOST_REPOSITORY_URL) }) {
                    Text(stringResource(R.string.selfhost_card_github))
                }
            }
            Text(
                text = stringResource(R.string.selfhost_card_agent_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.selfhost_card_agent_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                OutlinedButton(
                    onClick = { copyPrompt(context, prompt, copied) },
                    modifier = Modifier.testTag("selfhost-copy"),
                ) {
                    Text(stringResource(R.string.selfhost_card_copy))
                }
                TextButton(onClick = { promptShown = !promptShown }) {
                    Text(
                        stringResource(
                            if (promptShown) R.string.selfhost_card_hide_prompt
                            else R.string.selfhost_card_show_prompt
                        )
                    )
                }
            }
            if (promptShown) {
                Text(
                    text = prompt,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier =
                        Modifier.padding(top = 8.dp)
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}

private fun copyPrompt(context: Context, prompt: String, confirmation: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Stricknani setup prompt", prompt))
    Toast.makeText(context, confirmation, Toast.LENGTH_SHORT).show()
}
