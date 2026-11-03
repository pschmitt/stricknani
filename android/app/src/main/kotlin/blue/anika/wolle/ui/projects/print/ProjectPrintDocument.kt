package blue.anika.wolle.ui.projects.print

import blue.anika.wolle.data.api.dto.ImageDto
import blue.anika.wolle.data.api.dto.ProjectDto
import blue.anika.wolle.ui.common.normalizeMarkdownContent
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser

/** What a printout contains. */
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

/** Localised section headings and field names, resolved by the caller. */
data class PrintLabels(
    val details: String,
    val category: String,
    val needles: String,
    val yarn: String,
    val otherMaterials: String,
    val tags: String,
    val stitchSample: String,
    val linkedYarns: String,
    val description: String,
    val steps: String,
    val notes: String,
)

/**
 * Builds the self-contained HTML that is printed through Android's print framework. Pure and
 * synchronous: images are passed in as already-downloaded data URIs ([embeddedImages], keyed by
 * absolute URL), so the WebView needs no network access or credentials. Images without an entry are
 * left out rather than printed as broken frames.
 */
object ProjectPrintDocument {
    private val flavour = GFMFlavourDescriptor()
    private val imgTag = Regex("""<img\b[^>]*\bsrc="([^"]*)"[^>]*>""", RegexOption.IGNORE_CASE)

    /** Absolute URLs of every image the printout can show for [options]. */
    fun imageUrls(
        project: ProjectDto,
        options: PrintOptions,
        resolve: (String?) -> String?,
    ): List<String> {
        if (!options.includeImages) return emptyList()
        val texts = buildList {
            if (options.content == PrintContent.EVERYTHING) {
                project.description?.let(::add)
                project.notes?.let(::add)
            }
            project.steps.mapNotNullTo(this) { it.description }
        }
        val fromText = texts.flatMap { text ->
            imgTag.findAll(renderMarkdown(text)).map { it.groupValues[1] }.toList()
        }
        val gallery = imagesFor(project, options).map { it.url }
        return (gallery + fromText).mapNotNull(resolve).distinct()
    }

    fun html(
        project: ProjectDto,
        linkedYarnNames: List<String>,
        options: PrintOptions,
        labels: PrintLabels,
        resolve: (String?) -> String?,
        embeddedImages: Map<String, String>,
    ): String {
        fun images(list: List<ImageDto>): String =
            if (!options.includeImages) ""
            else
                list
                    .mapNotNull { image ->
                        val src =
                            resolve(image.url)?.let(embeddedImages::get) ?: return@mapNotNull null
                        """<img src="$src" alt="${escape(image.altText)}">"""
                    }
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString("", prefix = """<div class="images">""", postfix = "</div>")
                    .orEmpty()

        fun markdown(text: String?): String =
            text
                ?.takeIf { it.isNotBlank() }
                ?.let { inlineImages(renderMarkdown(it), options, resolve, embeddedImages) }
                .orEmpty()

        val body = StringBuilder()
        body.append("<h1>").append(escape(project.name)).append("</h1>")
        if (options.content == PrintContent.EVERYTHING) {
            body.append(images(project.images.filter { it.stepId == null && !it.isStitchSample }))
            val fields =
                listOf(
                        labels.category to project.category,
                        labels.needles to project.needles,
                        labels.yarn to project.yarn,
                        labels.otherMaterials to project.otherMaterials,
                        labels.tags to project.tags.joinToString(", ").ifBlank { null },
                    )
                    .filter { (_, value) -> !value.isNullOrBlank() }
            if (fields.isNotEmpty()) {
                body.append("<h2>").append(escape(labels.details)).append("</h2><dl>")
                fields.forEach { (label, value) ->
                    body.append("<dt>").append(escape(label)).append("</dt><dd>")
                    body.append(escape(value.orEmpty())).append("</dd>")
                }
                body.append("</dl>")
            }
            if (!project.stitchSample.isNullOrBlank()) {
                body.append("<h2>").append(escape(labels.stitchSample)).append("</h2>")
                body.append("<p>").append(escape(project.stitchSample)).append("</p>")
                body.append(
                    images(project.images.filter { it.stepId == null && it.isStitchSample })
                )
            }
            if (linkedYarnNames.isNotEmpty()) {
                body.append("<h2>").append(escape(labels.linkedYarns)).append("</h2><ul>")
                linkedYarnNames.forEach { body.append("<li>").append(escape(it)).append("</li>") }
                body.append("</ul>")
            }
            if (!project.description.isNullOrBlank()) {
                body.append("<h2>").append(escape(labels.description)).append("</h2>")
                body.append(markdown(project.description))
            }
        }
        val steps = project.steps.sortedBy { it.stepNumber }
        if (steps.isNotEmpty()) {
            body.append("<h2>").append(escape(labels.steps)).append("</h2>")
            steps.forEach { step ->
                body.append("""<section class="step"><h3>""")
                body.append(step.stepNumber).append(". ").append(escape(step.title)).append("</h3>")
                body.append(markdown(step.description))
                body.append(images(project.images.filter { it.stepId == step.id }))
                body.append("</section>")
            }
        }
        if (options.content == PrintContent.EVERYTHING && !project.notes.isNullOrBlank()) {
            body.append("<h2>").append(escape(labels.notes)).append("</h2>")
            body.append(markdown(project.notes))
        }
        return """<!doctype html><html><head><meta charset="utf-8"><title>${escape(project.name)}</title><style>$STYLE</style></head><body>$body</body></html>"""
    }

    private fun imagesFor(project: ProjectDto, options: PrintOptions): List<ImageDto> =
        when (options.content) {
            PrintContent.EVERYTHING -> project.images
            PrintContent.INSTRUCTIONS -> project.images.filter { it.stepId != null }
        }

    internal fun renderMarkdown(text: String): String {
        val source = normalizeMarkdownContent(text)
        val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(source)
        return HtmlGenerator(source, tree, flavour)
            .generateHtml()
            .removePrefix("<body>")
            .removeSuffix("</body>")
    }

    /** Swaps each `<img>` for its embedded copy, or drops it (no images, or not downloaded). */
    private fun inlineImages(
        html: String,
        options: PrintOptions,
        resolve: (String?) -> String?,
        embeddedImages: Map<String, String>,
    ): String =
        imgTag.replace(html) { match ->
            if (!options.includeImages) return@replace ""
            val data = resolve(match.groupValues[1])?.let(embeddedImages::get)
            if (data == null) "" else match.value.replace(match.groupValues[1], data)
        }

    internal fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private const val STYLE =
        "body{font-family:sans-serif;font-size:11pt;line-height:1.45;color:#000;margin:0}" +
            "h1{font-size:20pt;margin:0 0 8pt}h2{font-size:14pt;margin:16pt 0 4pt;" +
            "border-bottom:1px solid #999}h3{font-size:12pt;margin:10pt 0 2pt}" +
            "dl{display:grid;grid-template-columns:max-content 1fr;gap:2pt 12pt}dt{font-weight:bold}" +
            "dd{margin:0}.step{break-inside:avoid}.images{display:flex;flex-wrap:wrap;gap:6pt;" +
            "margin:6pt 0}img{max-width:100%;max-height:8cm;object-fit:contain}"
}
