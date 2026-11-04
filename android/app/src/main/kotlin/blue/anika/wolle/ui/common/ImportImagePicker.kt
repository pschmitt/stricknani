package blue.anika.wolle.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/**
 * Pictures found on an imported page, as a row of thumbnails with a checkbox each. The server
 * proxies the thumbnails ([thumbnailUrl]), so the app never contacts the remote site. With
 * [previews] off (the server has link imports turned off, so its proxy answers 503) each tile shows
 * the picture's host instead of a broken thumbnail.
 */
@Composable
fun ImportImagePicker(
    urls: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    thumbnailUrl: (String) -> String?,
    modifier: Modifier = Modifier,
    previews: Boolean = true,
) {
    LazyRow(
        modifier = modifier.testTag("import-image-picker"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(urls, key = { it }) { url ->
            Box(modifier = Modifier.size(96.dp).clip(RoundedCornerShape(12.dp))) {
                if (previews) {
                    AsyncImage(
                        model = thumbnailUrl(url),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(96.dp),
                    )
                } else {
                    Box(
                        modifier =
                            Modifier.size(96.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(start = 8.dp, end = 8.dp, top = 36.dp),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Text(
                            text = importImageHost(url),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Checkbox(
                    checked = url in selected,
                    onCheckedChange = { onToggle(url) },
                    modifier = Modifier.align(Alignment.TopStart),
                )
            }
        }
    }
}

/** The host of a remote picture, shown instead of its thumbnail when previews are off. */
fun importImageHost(remoteUrl: String): String =
    runCatching { java.net.URI(remoteUrl).host }.getOrNull()?.removePrefix("www.") ?: remoteUrl

/** `/media/...`-style path of the server's thumbnail proxy for a remote picture. */
fun importImagePath(remoteUrl: String): String =
    "/api/v2/imports/image?url=" + java.net.URLEncoder.encode(remoteUrl, "UTF-8")
