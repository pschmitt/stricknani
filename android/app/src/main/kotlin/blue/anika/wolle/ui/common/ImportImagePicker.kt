package blue.anika.wolle.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/**
 * Pictures found on an imported page, as a row of thumbnails with a checkbox each. The server
 * proxies the thumbnails ([thumbnailUrl]), so the app never contacts the remote site.
 */
@Composable
fun ImportImagePicker(
    urls: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    thumbnailUrl: (String) -> String?,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.testTag("import-image-picker"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(urls, key = { it }) { url ->
            Box(modifier = Modifier.size(96.dp).clip(RoundedCornerShape(12.dp))) {
                AsyncImage(
                    model = thumbnailUrl(url),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(96.dp),
                )
                Checkbox(
                    checked = url in selected,
                    onCheckedChange = { onToggle(url) },
                    modifier = Modifier.align(Alignment.TopStart),
                )
            }
        }
    }
}

/** `/media/...`-style path of the server's thumbnail proxy for a remote picture. */
fun importImagePath(remoteUrl: String): String =
    "/api/v2/imports/image?url=" + java.net.URLEncoder.encode(remoteUrl, "UTF-8")
