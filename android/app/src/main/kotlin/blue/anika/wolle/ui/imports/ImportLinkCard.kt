package blue.anika.wolle.ui.imports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import blue.anika.wolle.R
import blue.anika.wolle.ui.common.EditorSectionCard
import blue.anika.wolle.ui.common.ImportProgressPanel
import blue.anika.wolle.ui.common.PhosphorIcons
import blue.anika.wolle.ui.common.importFailureRes
import blue.anika.wolle.ui.projects.importWarningRes
import blue.anika.wolle.ui.theme.RaspberryNightWash
import blue.anika.wolle.ui.theme.RaspberryWash

/**
 * What a finished import tells besides the fields it filled: the page it came from, hints about the
 * result ([warnings] are the server's codes, see [importWarningRes]) and the AI notes.
 */
data class ImportNotice(
    val host: String,
    val warnings: List<String> = emptyList(),
    val aiFilled: Boolean = false,
    val aiFallback: Boolean = false,
)

/**
 * The compact card at the top of a new project or yarn: "Have a pattern? Paste a link". Starting
 * the import shows the progress here and the result fills the editor's own fields; the user may
 * leave meanwhile, the draft waits on the server. Afterwards it says where the fields came from.
 */
@Composable
fun ImportLinkCard(
    yarn: Boolean,
    state: EditorImportState,
    notice: ImportNotice?,
    ai: Boolean,
    onStart: (String) -> Unit,
    modifier: Modifier = Modifier,
    initialUrl: String = "",
) {
    var link by rememberSaveable(initialUrl) { mutableStateOf(initialUrl) }
    Column(modifier.fillMaxWidth().testTag("import-link-card")) {
        EditorSectionCard(
            title =
                stringResource(
                    if (yarn) R.string.import_card_title_yarn
                    else R.string.import_card_title_project
                ),
            icon = PhosphorIcons.DownloadSimple,
            // Tinted like the web's import card (--accent-wash), so it stands out from the form.
            containerColor =
                if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) RaspberryNightWash
                else RaspberryWash,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (state) {
                    is EditorImportState.Running ->
                        ImportProgressPanel(
                            ai = ai,
                            modifier = Modifier.testTag("import-link-progress"),
                        ) {
                            Text(
                                text = stringResource(R.string.import_background_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    is EditorImportState.Ready -> Unit
                    is EditorImportState.Idle,
                    is EditorImportState.Failed -> {
                        if (notice != null) ImportNoticeLines(notice, yarn)
                        if (state is EditorImportState.Failed) {
                            Text(
                                stringResource(importFailureRes(state.failure, yarn)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.testTag("import-link-error"),
                            )
                        }
                        val start = { if (link.isNotBlank()) onStart(link) }
                        // As on the web: the link on its own line, the button below it.
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalAlignment = Alignment.End,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            OutlinedTextField(
                                value = link,
                                onValueChange = {
                                    link = it.filterNot { c -> c == '\n' || c == '\r' }
                                },
                                label = {
                                    Text(
                                        stringResource(
                                            if (yarn) R.string.yarn_import_url_label
                                            else R.string.project_import_url_label
                                        )
                                    )
                                },
                                placeholder = { Text("https://") },
                                singleLine = true,
                                keyboardOptions =
                                    KeyboardOptions(
                                        keyboardType = KeyboardType.Uri,
                                        imeAction = ImeAction.Go,
                                    ),
                                keyboardActions = KeyboardActions(onGo = { start() }),
                                modifier = Modifier.fillMaxWidth().testTag("import-link-url"),
                            )
                            Button(
                                onClick = start,
                                enabled = link.isNotBlank(),
                                modifier = Modifier.testTag("import-link-start"),
                            ) {
                                Text(stringResource(R.string.project_import_start))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportNoticeLines(notice: ImportNotice, yarn: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(
                if (yarn) R.string.import_card_done_yarn else R.string.import_card_done_project
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        notice.warnings
            .mapNotNull { importWarningRes(it) }
            .forEach {
                Text(
                    stringResource(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        if (notice.aiFilled) {
            Text(
                stringResource(R.string.import_ai_filled_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (notice.aiFallback) {
            Text(
                stringResource(R.string.project_import_ai_fallback),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
