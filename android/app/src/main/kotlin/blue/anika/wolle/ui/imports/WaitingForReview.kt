package blue.anika.wolle.ui.imports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import blue.anika.wolle.R
import blue.anika.wolle.data.api.v2.ImportJobDto
import blue.anika.wolle.data.imports.ImportDrafts
import blue.anika.wolle.data.imports.ImportTarget
import blue.anika.wolle.data.media.MediaUrlResolver
import blue.anika.wolle.ui.common.PhosphorIcons
import blue.anika.wolle.ui.common.importImagePath
import blue.anika.wolle.ui.common.urlHostOrNull
import coil3.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The server's import drafts for the "waiting for review" cards; asks while the cards are shown.
 */
@HiltViewModel
class WaitingDraftsViewModel
@Inject
constructor(private val drafts: ImportDrafts, private val media: MediaUrlResolver) : ViewModel() {
    val items: StateFlow<List<ImportJobDto>> =
        flow {
                coroutineScope {
                    launch { drafts.watch().collect() }
                    drafts.drafts.collect { emit(it) }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), drafts.drafts.value)

    /** The server's thumbnail proxy for the draft's picture. */
    fun imageUrl(draft: ImportJobDto): String? =
        draft.imageUrl?.let { media.resolve(importImagePath(it)) }

    fun dismiss(draft: ImportJobDto) {
        viewModelScope.launch { runCatching { drafts.delete(draft.id) } }
    }
}

/**
 * "Waiting for review": the imports the server has run (or is running) that the user has not saved
 * or dismissed yet. [target] limits them to projects or yarns; null shows all (Home). Tapping one
 * opens the editor on it, the cross dismisses it. Nothing is shown when there are none.
 */
@Composable
fun WaitingForReview(
    target: ImportTarget?,
    onOpen: (ImportJobDto) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WaitingDraftsViewModel = hiltViewModel(),
) {
    val all by viewModel.items.collectAsStateWithLifecycle()
    val shown = all.filter { target == null || it.target == target.wire }
    if (shown.isEmpty()) return
    Column(
        modifier.fillMaxWidth().testTag("waiting-for-review"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.import_waiting_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 4.dp),
        )
        shown.forEach { draft ->
            WaitingDraftCard(
                draft = draft,
                imageUrl = viewModel.imageUrl(draft),
                onOpen = { onOpen(draft) },
                onDismiss = { viewModel.dismiss(draft) },
            )
        }
    }
}

/** One waiting import: picture, title, status ("Importing…", "Ready to review", "Did not work"). */
@Composable
fun WaitingDraftCard(
    draft: ImportJobDto,
    imageUrl: String?,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title =
        draft.title?.takeIf { it.isNotBlank() }
            ?: draft.sourceUrl?.let { urlHostOrNull(it)?.removePrefix("www.") ?: it }.orEmpty()
    val status =
        stringResource(
            when {
                draft.isReady -> R.string.import_waiting_ready
                draft.isFailed -> R.string.import_waiting_failed
                else -> R.string.import_waiting_running
            }
        )
    Surface(
        onClick = onOpen,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth().testTag("waiting-draft-${draft.id}"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        ) {
            Box(
                Modifier.size(52.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (imageUrl != null) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(52.dp),
                    )
                } else {
                    Icon(
                        PhosphorIcons.DownloadSimple,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (draft.isFailed) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (draft.isRunning) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }
            IconButton(onClick = onDismiss, modifier = Modifier.testTag("waiting-dismiss")) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.import_waiting_dismiss, title),
                )
            }
        }
    }
}
