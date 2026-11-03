package blue.anika.wolle.ui.yarns.print

import blue.anika.wolle.data.api.dto.YarnDto
import blue.anika.wolle.data.api.dto.primaryFirst
import blue.anika.wolle.ui.print.PrintHtml

/** Localised headings and field names for a yarn printout, resolved by the caller. */
data class YarnPrintLabels(
    val details: String,
    val brand: String,
    val colorway: String,
    val dyeLot: String,
    val fiberContent: String,
    val weightCategory: String,
    val recommendedNeedles: String,
    val amount: String,
    val link: String,
    val description: String,
    val notes: String,
    val usedIn: String,
)

/**
 * Builds the self-contained HTML for printing a yarn - a label for the stash box or a page for the
 * project folder. Same contract as `ProjectPrintDocument`: images arrive as data URIs keyed by
 * absolute URL, and ones that are missing are left out.
 */
object YarnPrintDocument {

    fun imageUrls(
        yarn: YarnDto,
        includeImages: Boolean,
        resolve: (String?) -> String?,
    ): List<String> {
        if (!includeImages) return emptyList()
        val fromText =
            listOfNotNull(yarn.description, yarn.notes).flatMap {
                PrintHtml.imageSources(PrintHtml.renderMarkdown(it))
            }
        return (yarn.photos.primaryFirst().map { it.url } + fromText).mapNotNull(resolve).distinct()
    }

    fun html(
        yarn: YarnDto,
        usedInProjectNames: List<String>,
        includeImages: Boolean,
        labels: YarnPrintLabels,
        resolve: (String?) -> String?,
        embeddedImages: Map<String, String>,
    ): String {
        fun markdown(text: String?): String =
            text
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    PrintHtml.inlineImages(
                        PrintHtml.renderMarkdown(it),
                        includeImages,
                        resolve,
                        embeddedImages,
                    )
                }
                .orEmpty()

        val amount =
            listOfNotNull(yarn.weightGrams?.let { "${it} g" }, yarn.lengthMeters?.let { "${it} m" })
                .joinToString(" / ")
        val body = StringBuilder()
        body.append("<h1>").append(PrintHtml.escape(yarn.name)).append("</h1>")
        if (includeImages) {
            body.append(
                PrintHtml.imageRow(
                    yarn.photos.primaryFirst().mapNotNull { photo ->
                        resolve(photo.url)?.let { it to photo.altText }
                    },
                    embeddedImages,
                )
            )
        }
        body.append(
            PrintHtml.fields(
                labels.details,
                listOf(
                    labels.brand to yarn.brand,
                    labels.colorway to yarn.colorway,
                    labels.dyeLot to yarn.dyeLot,
                    labels.fiberContent to yarn.fiberContent,
                    labels.weightCategory to yarn.weightCategory,
                    labels.recommendedNeedles to yarn.recommendedNeedles,
                    labels.amount to amount,
                    labels.link to yarn.link,
                ),
            )
        )
        if (!yarn.description.isNullOrBlank()) {
            body.append("<h2>").append(PrintHtml.escape(labels.description)).append("</h2>")
            body.append(markdown(yarn.description))
        }
        if (usedInProjectNames.isNotEmpty()) {
            body.append("<h2>").append(PrintHtml.escape(labels.usedIn)).append("</h2><ul>")
            usedInProjectNames.forEach {
                body.append("<li>").append(PrintHtml.escape(it)).append("</li>")
            }
            body.append("</ul>")
        }
        if (!yarn.notes.isNullOrBlank()) {
            body.append("<h2>").append(PrintHtml.escape(labels.notes)).append("</h2>")
            body.append(markdown(yarn.notes))
        }
        return PrintHtml.document(yarn.name, body.toString())
    }
}
