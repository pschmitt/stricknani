package blue.anika.wolle.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
internal class MarkdownImageTransformer(
    private val resolveUrl: (String) -> String?,
    private val onImageClick: (String) -> Unit = {},
    private val imageReferences: List<MarkdownImageReference> = emptyList(),
) :
    ImageTransformer {
    internal fun resolve(link: String): String? = resolveUrl(link)

    internal fun referenceFor(link: String): MarkdownImageReference? =
        imageReferences.firstOrNull { it.source == link }

    @Composable
    override fun transform(link: String): ImageData? {
        val resolved = resolve(link) ?: return null
        val reference = referenceFor(link)
        val imageData = Coil3ImageTransformerImpl.transform(resolved)
        val modifier =
            imageData.modifier
                .then(Modifier.fillMaxWidth(reference?.size?.widthFraction ?: MarkdownImageSize.SM.widthFraction))
                .then(Modifier.clickable { onImageClick(resolved) })
                .then(
                    Modifier.semantics {
                        contentDescription =
                            reference?.altText?.takeIf(String::isNotBlank)
                                ?: "Open image"
                    }
                )
        return imageData.copy(
            modifier = modifier,
            contentDescription = reference?.altText?.takeIf(String::isNotBlank),
        )
    }

    @Composable
    override fun intrinsicSize(painter: Painter): Size =
        Coil3ImageTransformerImpl.intrinsicSize(painter)
}
