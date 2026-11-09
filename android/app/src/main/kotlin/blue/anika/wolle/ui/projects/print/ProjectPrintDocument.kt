package blue.anika.wolle.ui.projects.print

import blue.anika.wolle.data.api.dto.ImageDto
import blue.anika.wolle.data.api.dto.ProjectDto
import blue.anika.wolle.ui.print.PrintContent
import blue.anika.wolle.ui.print.PrintHtml
import blue.anika.wolle.ui.print.PrintOptions

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
 * Builds the self-contained HTML for printing a project through Android's print framework. Pure and
 * synchronous: images are passed in as already-downloaded data URIs ([embeddedImages], keyed by
 * absolute URL), so the WebView needs no network access or credentials. Images without an entry are
 * left out rather than printed as broken frames.
 */
object ProjectPrintDocument {
    /** A single line break inside a paragraph (not a blank line). */
    private val SOFT_BREAK = Regex("(?<!\n)\n(?!\n)")

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
        val fromText = texts.flatMap { PrintHtml.imageSources(PrintHtml.renderMarkdown(it)) }
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
                PrintHtml.imageRow(
                    list.mapNotNull { image -> resolve(image.url)?.let { it to image.altText } },
                    embeddedImages,
                )

        fun markdown(text: String?): String =
            text
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    PrintHtml.inlineImages(
                        PrintHtml.renderMarkdown(it),
                        options.includeImages,
                        resolve,
                        embeddedImages,
                    )
                }
                .orEmpty()

        val body = StringBuilder()
        body.append("<h1>").append(PrintHtml.escape(project.name)).append("</h1>")
        if (options.content == PrintContent.EVERYTHING) {
            body.append(images(project.images.filter { it.stepId == null && !it.isStitchSample }))
            body.append(
                PrintHtml.fields(
                    labels.details,
                    listOf(
                        labels.category to project.category,
                        labels.needles to project.needles,
                        labels.yarn to project.yarn,
                        labels.otherMaterials to project.otherMaterials,
                        labels.tags to project.tags.joinToString(", "),
                    ),
                )
            )
            if (!project.stitchSample.isNullOrBlank()) {
                body.append("<h2>").append(PrintHtml.escape(labels.stitchSample)).append("</h2>")
                // Every line stays its own line, like the reader (the renderer trims trailing
                // blanks, so a hard break cannot be written): one paragraph per line.
                body.append(markdown(project.stitchSample.replace(SOFT_BREAK, "\n\n")))
                body.append(
                    images(project.images.filter { it.stepId == null && it.isStitchSample })
                )
            }
            if (linkedYarnNames.isNotEmpty()) {
                body.append("<h2>").append(PrintHtml.escape(labels.linkedYarns)).append("</h2><ul>")
                linkedYarnNames.forEach {
                    body.append("<li>").append(PrintHtml.escape(it)).append("</li>")
                }
                body.append("</ul>")
            }
            if (!project.description.isNullOrBlank()) {
                body.append("<h2>").append(PrintHtml.escape(labels.description)).append("</h2>")
                body.append(markdown(project.description))
            }
        }
        val steps = project.steps.sortedBy { it.stepNumber }
        if (steps.isNotEmpty()) {
            body.append("<h2>").append(PrintHtml.escape(labels.steps)).append("</h2>")
            steps.forEach { step ->
                body.append("""<section class="step"><h3>""")
                body.append(step.stepNumber).append(". ").append(PrintHtml.escape(step.title))
                body.append("</h3>")
                body.append(markdown(step.description))
                body.append(images(project.images.filter { it.stepId == step.id }))
                body.append("</section>")
            }
        }
        if (options.content == PrintContent.EVERYTHING && !project.notes.isNullOrBlank()) {
            body.append("<h2>").append(PrintHtml.escape(labels.notes)).append("</h2>")
            body.append(markdown(project.notes))
        }
        return PrintHtml.document(project.name, body.toString())
    }

    private fun imagesFor(project: ProjectDto, options: PrintOptions): List<ImageDto> =
        when (options.content) {
            PrintContent.EVERYTHING -> project.images
            PrintContent.INSTRUCTIONS -> project.images.filter { it.stepId != null }
        }
}
