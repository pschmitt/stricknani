@file:OptIn(ExperimentalRichTextApi::class)

package blue.anika.wolle.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.model.ImageData
import com.mohamedrejeb.richeditor.model.ImageLoader

/**
 * A picture of the visual editor: the Markdown exactly as it was read (`![alt](url){.sn-size-md}`,
 * written back verbatim so nothing about it changes) and its alt text for display.
 */
internal data class EditorImage(val markdown: String, val alt: String) {
    /** The destination as written in [markdown] (`/media/...`, absolute), or null if unparsable. */
    val source: String?
        get() = parse(markdown)?.second

    /** [source] resolved against the configured server, null when it is unsafe or unusable. */
    fun resolvedSource(resolveUrl: (String?) -> String?): String? = source?.let(resolveUrl)

    companion object {
        private val escapes = Regex("\\\\([!-/:-@\\[-`{-~])")
        private val syntax =
            Regex(
                "^!\\[((?:\\\\.|[^\\]\\\\])*)\\]\\(\\s*(?:<([^>\\r\\n]*)>|((?:\\\\.|[^\\s)\\\\])+))"
            )

        fun unescapeAlt(raw: String): String = escapes.replace(raw) { it.groupValues[1] }

        /** (alt, destination) of a picture's Markdown, null when it is not one. */
        fun parse(markdown: String): Pair<String, String>? {
            val match = syntax.find(markdown) ?: return null
            val alt = unescapeAlt(match.groupValues[1])
            val destination = unescapeAlt(match.groups[2]?.value ?: match.groups[3]?.value ?: "")
            return alt to destination
        }

        /**
         * The Markdown of a picture span. Pictures read from Markdown carry their original text; a
         * picture the library created (a paste) has a bare address, written with the same alt
         * escaping and destination rules as the rest of the writer.
         */
        fun markdownOf(url: String, description: String?): String {
            if (url.startsWith("![")) return url
            val alt = MarkdownWriter.escape(description.orEmpty().replace('\n', ' '), false)
            val destination = url.replace(" ", "%20").replace("(", "%28").replace(")", "%29")
            return "![$alt]($destination)"
        }
    }
}

/**
 * Loads the pictures of the visual editor with the app's Coil (authenticated media client, shared
 * disk cache), through the same resolver as the reader's Markdown images. While offline, unloadable
 * or unsafe, the picture is a box with its alt text.
 */
internal class EditorImageLoader(private val resolveUrl: (String?) -> String?) : ImageLoader {
    @Composable
    override fun load(model: Any): ImageData {
        val image = remember(model) { EditorImage(model.toString(), alt(model.toString())) }
        val url = remember(image, resolveUrl) { image.resolvedSource(resolveUrl) }
        val context = LocalContext.current
        val request = remember(url) { ImageRequest.Builder(context).data(url).build() }
        val painter = rememberAsyncImagePainter(request)
        val state by painter.state.collectAsState()
        val failed = url == null || state is AsyncImagePainter.State.Error
        val placeholder =
            rememberAltPlaceholder(
                image.alt,
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.colorScheme.onSurfaceVariant,
            )
        return ImageData(
            painter = if (failed) placeholder else painter,
            contentDescription = image.alt.ifBlank { null },
            contentScale = ContentScale.Fit,
            modifier = Modifier,
        )
    }

    private fun alt(markdown: String) = EditorImage.parse(markdown)?.first.orEmpty()
}

@Composable
private fun rememberAltPlaceholder(alt: String, background: Color, ink: Color): Painter {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = MaterialTheme.typography.bodySmall
    return remember(alt, background, ink, density, style) {
        AltPlaceholderPainter(
            alt,
            measurer,
            style.copy(color = ink),
            background,
            Size(density.run { 280.dp.toPx() }, density.run { 96.dp.toPx() }),
        )
    }
}

/** A rounded box with the picture's alt text, shown where the picture cannot be loaded. */
private class AltPlaceholderPainter(
    private val alt: String,
    private val measurer: TextMeasurer,
    private val style: TextStyle,
    private val background: Color,
    override val intrinsicSize: Size,
) : Painter() {
    override fun DrawScope.onDraw() {
        drawRoundRect(background, size = size, cornerRadius = CornerRadius(8f, 8f))
        if (alt.isBlank()) return
        val inset = 12f
        val layout =
            measurer.measure(
                alt,
                style,
                overflow = TextOverflow.Ellipsis,
                constraints =
                    Constraints(maxWidth = (size.width - 2 * inset).toInt().coerceAtLeast(1)),
            )
        drawText(
            layout,
            topLeft =
                Offset(
                    inset,
                    ((size.height - layout.size.height) / 2f).coerceAtLeast(0f),
                ),
        )
    }
}
