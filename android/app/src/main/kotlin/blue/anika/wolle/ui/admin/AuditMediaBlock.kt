package blue.anika.wolle.ui.admin

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import blue.anika.wolle.R
import blue.anika.wolle.data.api.dto.AuditMediaDto
import blue.anika.wolle.data.media.AuditFileDownloader
import blue.anika.wolle.data.media.MediaUrlResolver
import blue.anika.wolle.data.uploads.captureAuthority
import coil3.compose.SubcomposeAsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class AuditMediaViewModel
@Inject
constructor(
    private val downloader: AuditFileDownloader,
    private val resolver: MediaUrlResolver,
) : ViewModel() {
    fun resolve(path: String?): String? = resolver.resolve(path)

    suspend fun download(media: AuditMediaDto): File? = downloader.download(media)
}

/** "name · Picture" or "name · File · deleted": the one-line description of an event's media. */
@Composable
fun auditMediaNote(media: AuditMediaDto): String {
    val kind =
        stringResource(
            if (media.isImage) R.string.admin_media_picture else R.string.admin_media_file
        )
    val name = media.displayName() ?: kind
    return if (media.state() is AuditMediaState.Deleted) {
        stringResource(
            R.string.admin_media_note_deleted,
            name,
            kind,
            stringResource(R.string.admin_media_deleted_suffix),
        )
    } else {
        stringResource(R.string.admin_media_note, name, kind)
    }
}

/**
 * The picture or file an upload/removal event was about, full size: the picture itself, an "Open
 * file" button for other files, or a clear "deleted" note once the file is gone.
 */
@Composable
fun AuditMediaBlock(media: AuditMediaDto, modifier: Modifier = Modifier) {
    val viewModel: AuditMediaViewModel = hiltViewModel()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val failed = stringResource(R.string.admin_media_open_failed)
    Column(
        modifier.fillMaxWidth().testTag("audit-media"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            auditMediaNote(media),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        when (val state = media.state()) {
            is AuditMediaState.Picture -> {
                val picture = viewModel.resolve(state.url)
                if (picture != null) {
                    SubcomposeAsyncImage(
                        model = picture,
                        contentDescription = media.displayName(),
                        contentScale = ContentScale.Fit,
                        modifier =
                            Modifier.fillMaxWidth()
                                .heightIn(max = 420.dp)
                                .clip(MaterialTheme.shapes.medium)
                                .testTag("audit-media-picture"),
                        loading = { Box(Modifier.fillMaxWidth().heightIn(min = 120.dp)) },
                        error = {
                            Text(
                                stringResource(R.string.admin_media_picture_deleted),
                                modifier = Modifier.padding(8.dp),
                            )
                        },
                    )
                }
            }
            is AuditMediaState.File ->
                Button(
                    modifier = Modifier.align(Alignment.Start).heightIn(min = 48.dp),
                    onClick = {
                        scope.launch {
                            val file = viewModel.download(media)
                            val opened =
                                file != null &&
                                    runCatching {
                                            val uri =
                                                FileProvider.getUriForFile(
                                                    context,
                                                    captureAuthority(context),
                                                    file,
                                                )
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW)
                                                    .setDataAndType(
                                                        uri,
                                                        media.contentType ?: "*/*",
                                                    )
                                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            )
                                        }
                                        .isSuccess
                            if (!opened) Toast.makeText(context, failed, Toast.LENGTH_SHORT).show()
                        }
                    },
                ) {
                    Text(stringResource(R.string.admin_media_open_file))
                }
            is AuditMediaState.Deleted ->
                Text(
                    stringResource(
                        if (state.isImage) R.string.admin_media_picture_deleted
                        else R.string.admin_media_file_deleted
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
        }
    }
}

/**
 * A small thumbnail of the picture an upload/removal event was about, for list rows. Nothing is
 * drawn for files, deleted pictures or a url that cannot be resolved, so the row keeps its text.
 */
@Composable
fun AuditMediaThumb(media: AuditMediaDto?, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val state = media?.state() as? AuditMediaState.Picture ?: return
    val viewModel: AuditMediaViewModel = hiltViewModel()
    val picture = viewModel.resolve(state.thumbnailUrl ?: state.url) ?: return
    SubcomposeAsyncImage(
        model = picture,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier =
            modifier.size(size).clip(MaterialTheme.shapes.small).testTag("audit-media-thumb"),
        loading = {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest)
            )
        },
        error = {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest)
            )
        },
    )
}
