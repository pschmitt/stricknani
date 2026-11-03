package blue.anika.wolle.ui.print

import android.util.Base64
import blue.anika.wolle.di.MediaClient
import blue.anika.wolle.ui.common.normalizeMarkdownContent
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser

/** What a project printout contains. Yarns always print everything. */
enum class PrintContent {
    /** Details, stitch sample, yarns, description, instructions and notes. */
    EVERYTHING,

    /** Only the numbered instruction steps - the part people knit from. */
    INSTRUCTIONS,
}

data class PrintOptions(
    val content: PrintContent = PrintContent.EVERYTHING,
    val includeImages: Boolean = true,
)

/** HTML building blocks shared by the project and yarn printouts. */
object PrintHtml {
    private val flavour = GFMFlavourDescriptor()
    private val imgTag = Regex("""<img\b[^>]*\bsrc="([^"]*)"[^>]*>""", RegexOption.IGNORE_CASE)

    fun renderMarkdown(text: String): String {
        val source = normalizeMarkdownContent(text)
        val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(source)
        return HtmlGenerator(source, tree, flavour)
            .generateHtml()
            .removePrefix("<body>")
            .removeSuffix("</body>")
    }

    /** `src` values of the images in rendered Markdown. */
    fun imageSources(html: String): List<String> =
        imgTag.findAll(html).map { it.groupValues[1] }.toList()

    /** Swaps each `<img>` for its embedded copy, or drops it (no images, or not downloaded). */
    fun inlineImages(
        html: String,
        includeImages: Boolean,
        resolve: (String?) -> String?,
        embeddedImages: Map<String, String>,
    ): String =
        imgTag.replace(html) { match ->
            if (!includeImages) return@replace ""
            val data = resolve(match.groupValues[1])?.let(embeddedImages::get)
            if (data == null) "" else match.value.replace(match.groupValues[1], data)
        }

    /** A row of embedded images, or nothing when none of them were downloaded. */
    fun imageRow(images: List<Pair<String, String>>, embeddedImages: Map<String, String>): String =
        images
            .mapNotNull { (url, alt) ->
                embeddedImages[url]?.let { src -> """<img src="$src" alt="${escape(alt)}">""" }
            }
            .takeIf { it.isNotEmpty() }
            ?.joinToString("", prefix = """<div class="images">""", postfix = "</div>")
            .orEmpty()

    /** A definition list of the fields that have a value, or nothing. */
    fun fields(heading: String, fields: List<Pair<String, String?>>): String {
        val present = fields.filter { (_, value) -> !value.isNullOrBlank() }
        if (present.isEmpty()) return ""
        return buildString {
            append("<h2>").append(escape(heading)).append("</h2><dl>")
            present.forEach { (label, value) ->
                append("<dt>").append(escape(label)).append("</dt><dd>")
                append(escape(value.orEmpty())).append("</dd>")
            }
            append("</dl>")
        }
    }

    fun document(title: String, body: String): String =
        """<!doctype html><html><head><meta charset="utf-8"><title>${escape(title)}</title><style>$STYLE</style></head><body>$body</body></html>"""

    fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private const val STYLE =
        "body{font-family:sans-serif;font-size:11pt;line-height:1.45;color:#000;margin:0}" +
            "h1{font-size:20pt;margin:0 0 8pt}h2{font-size:14pt;margin:16pt 0 4pt;" +
            "border-bottom:1px solid #999}h3{font-size:12pt;margin:10pt 0 2pt}" +
            "dl{display:grid;grid-template-columns:max-content 1fr;gap:2pt 12pt}dt{font-weight:bold}" +
            "dd{margin:0}.step{break-inside:avoid}.images{display:flex;flex-wrap:wrap;gap:6pt;" +
            "margin:6pt 0}img{max-width:100%;max-height:8cm;object-fit:contain}"
}

/**
 * Downloads images for a printout with the app's credentials and returns them as data URIs keyed by
 * URL. An image that cannot be fetched (offline, deleted) is simply missing from the map, so the
 * printout leaves it out instead of failing.
 */
class PrintImageLoader @Inject constructor(@MediaClient private val mediaClient: OkHttpClient) {
    suspend fun embed(urls: List<String>): Map<String, String> =
        withContext(Dispatchers.IO) {
            urls.distinct().mapNotNull { url -> download(url)?.let { url to it } }.toMap()
        }

    private fun download(url: String): String? =
        runCatching {
                mediaClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    val body = response.body
                    val type = body.contentType()?.toString().orEmpty()
                    if (!response.isSuccessful || !type.startsWith("image/")) return null
                    "data:$type;base64," + Base64.encodeToString(body.bytes(), Base64.NO_WRAP)
                }
            }
            .getOrNull()
}
