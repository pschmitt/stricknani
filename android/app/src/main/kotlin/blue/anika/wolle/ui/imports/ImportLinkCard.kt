package blue.anika.wolle.ui.imports

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import blue.anika.wolle.R
import blue.anika.wolle.data.api.v2.IMPORT_FILE_MIME_TYPES
import blue.anika.wolle.ui.common.EditorSectionCard
import blue.anika.wolle.ui.common.ImportProgressPanel
import blue.anika.wolle.ui.common.PhosphorIcons
import blue.anika.wolle.ui.common.importFailureRes
import blue.anika.wolle.ui.projects.importWarningRes
import blue.anika.wolle.ui.share.SharedLink
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

/** Where an import of a new project comes from, in the order of the web's source choice. */
enum class ImportSource {
    Link,
    File,
    Text,
}

/** A document picked in the system picker, with the name the card shows. */
data class PickedImportFile(val uri: Uri, val name: String)

/**
 * The compact card at the top of a new project or yarn: "Have a pattern? Paste a link, upload a
 * file or paste text". A project chooses between a link, a file (the system document picker) and
 * pasted text, as on the web; a yarn is link-only. Starting the import shows the progress here and
 * the result fills the editor's own fields; the user may leave meanwhile, a link or text import
 * waits as a draft on the server. Afterwards it says where the fields came from.
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
    /** Link imports are on; without them a project falls back to text, as on the web. */
    allowLink: Boolean = true,
    onStartText: (String) -> Unit = {},
    onStartFile: (PickedImportFile) -> Unit = {},
) {
    var link by rememberSaveable(initialUrl) { mutableStateOf(initialUrl) }
    var text by rememberSaveable { mutableStateOf("") }
    var pickedUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var pickedName by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable {
        mutableStateOf(
            if (yarn || allowLink || initialUrl.isNotBlank()) ImportSource.Link
            else ImportSource.Text
        )
    }
    val context = LocalContext.current
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                pickedUri = uri
                pickedName = displayName(context, uri)
            }
        }
    val clipboard = LocalClipboardManager.current
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
                        if (!yarn) {
                            ImportSourceChoice(
                                source = source,
                                allowLink = allowLink,
                                onChange = { source = it },
                            )
                        }
                        val kind = if (yarn) ImportSource.Link else source
                        val canStart = canStartImport(kind, link, text, pickedUri != null)
                        val start = {
                            if (canStart) {
                                when (kind) {
                                    ImportSource.Link -> onStart(link)
                                    ImportSource.Text -> onStartText(text)
                                    ImportSource.File ->
                                        pickedUri?.let {
                                            onStartFile(PickedImportFile(it, pickedName))
                                        }
                                }
                            }
                        }
                        // As on the web: the source on its own line, the button below it.
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalAlignment = Alignment.End,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            when (kind) {
                                ImportSource.Link ->
                                    LinkField(
                                        yarn = yarn,
                                        link = link,
                                        onChange = { link = it },
                                        onPaste = {
                                            val clip = clipboard.getText()?.text.orEmpty()
                                            val pasted = SharedLink.extract(clip) ?: clip.trim()
                                            if (pasted.isNotEmpty()) link = pasted
                                        },
                                        onGo = { start() },
                                    )
                                ImportSource.Text ->
                                    OutlinedTextField(
                                        value = text,
                                        onValueChange = { text = it },
                                        label = {
                                            Text(stringResource(R.string.import_text_label))
                                        },
                                        placeholder = {
                                            Text(stringResource(R.string.import_text_placeholder))
                                        },
                                        supportingText = {
                                            Text(stringResource(R.string.import_size_limit))
                                        },
                                        minLines = 5,
                                        maxLines = 12,
                                        modifier =
                                            Modifier.fillMaxWidth().testTag("import-text-input"),
                                    )
                                ImportSource.File ->
                                    OutlinedButton(
                                        onClick = { picker.launch(IMPORT_FILE_MIME_TYPES) },
                                        modifier =
                                            Modifier.fillMaxWidth().testTag("import-file-pick"),
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(
                                                pickedName.ifBlank {
                                                    stringResource(R.string.import_file_choose)
                                                },
                                                style = MaterialTheme.typography.titleSmall,
                                            )
                                            Text(
                                                stringResource(R.string.import_file_hint),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                            }
                            Button(
                                onClick = { start() },
                                enabled = canStart,
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

/** Whether the chosen source holds something to import: the button is disabled until it does. */
internal fun canStartImport(
    source: ImportSource,
    link: String,
    text: String,
    fileChosen: Boolean,
): Boolean =
    when (source) {
        ImportSource.Link -> link.isNotBlank()
        ImportSource.Text -> text.isNotBlank()
        ImportSource.File -> fileChosen
    }

/** "From a link", "Upload file", "Paste text": the web's source choice, same order and icons. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImportSourceChoice(
    source: ImportSource,
    allowLink: Boolean,
    onChange: (ImportSource) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().testTag("import-source-choice"),
    ) {
        SourceChip(
            ImportSource.Link,
            source,
            R.string.import_source_link,
            PhosphorIcons.Link,
            allowLink,
            onChange,
        )
        SourceChip(
            ImportSource.File,
            source,
            R.string.import_source_file,
            PhosphorIcons.CloudArrowUp,
            true,
            onChange,
        )
        SourceChip(
            ImportSource.Text,
            source,
            R.string.import_source_text,
            Icons.Outlined.ContentPaste,
            true,
            onChange,
        )
    }
}

@Composable
private fun SourceChip(
    kind: ImportSource,
    selected: ImportSource,
    @StringRes label: Int,
    icon: ImageVector,
    enabled: Boolean,
    onChange: (ImportSource) -> Unit,
) {
    FilterChip(
        selected = kind == selected,
        onClick = { onChange(kind) },
        enabled = enabled,
        label = { Text(stringResource(label)) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
        modifier = Modifier.testTag("import-source-${kind.name.lowercase()}"),
    )
}

@Composable
private fun LinkField(
    yarn: Boolean,
    link: String,
    onChange: (String) -> Unit,
    onPaste: () -> Unit,
    onGo: () -> Unit,
) {
    OutlinedTextField(
        value = link,
        onValueChange = { onChange(it.filterNot { c -> c == '\n' || c == '\r' }) },
        label = {
            Text(
                stringResource(
                    if (yarn) R.string.yarn_import_url_label else R.string.project_import_url_label
                )
            )
        },
        placeholder = { Text("https://") },
        // Fills the link from the clipboard: its first web link, as a share would (a "title
        // link" text works too).
        trailingIcon = {
            IconButton(onClick = onPaste, modifier = Modifier.testTag("import-link-paste")) {
                Icon(
                    Icons.Outlined.ContentPaste,
                    contentDescription = stringResource(R.string.import_paste),
                )
            }
        },
        singleLine = true,
        keyboardOptions =
            KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { onGo() }),
        modifier = Modifier.fillMaxWidth().testTag("import-link-url"),
    )
}

private fun displayName(context: Context, uri: Uri): String =
    runCatching {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }
        .getOrNull()
        ?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment.orEmpty()

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
