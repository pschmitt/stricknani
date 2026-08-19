package blue.anika.wolle.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.painter.Painter
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer

/**
 * Markdown image adapter for Stricknani media URLs.
 *
 * The renderer passes the source exactly as it appears in Markdown. Resolve it before delegating to
 * Coil so `/media/...`, `media/...`, and safe same-origin absolute URLs use the same authenticated
 * ImageLoader and disk cache as the rest of the app. Returning null is the renderer-supported safe
 * fallback for malformed, unsupported, or cross-origin destinations.
 */
internal class MarkdownImageTransformer(private val resolveUrl: (String) -> String?) :
    ImageTransformer {
    internal fun resolve(link: String): String? = resolveUrl(link)

    @Composable
    override fun transform(link: String): ImageData? {
        val resolved = resolve(link) ?: return null
        return Coil3ImageTransformerImpl.transform(resolved)
    }

    @Composable
    override fun intrinsicSize(painter: Painter): Size =
        Coil3ImageTransformerImpl.intrinsicSize(painter)
}
