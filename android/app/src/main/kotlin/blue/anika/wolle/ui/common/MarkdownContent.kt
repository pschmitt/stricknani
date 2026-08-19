package blue.anika.wolle.ui.common

import blue.anika.wolle.data.util.decodeHtmlEntities

private val HTML_IMAGE = Regex("<img\\b([^>]*)/?>", RegexOption.IGNORE_CASE)
private val HTML_ATTRIBUTE =
    Regex("(?:^|\\s)([A-Za-z][A-Za-z0-9:_-]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))")
private val HTML_BREAK = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
private val HTML_PARAGRAPH_END =
    Regex("</(?:p|div|section|article|h[1-6]|blockquote)\\s*>", RegexOption.IGNORE_CASE)
private val HTML_LIST_ITEM_START = Regex("<li\\b[^>]*>", RegexOption.IGNORE_CASE)
private val HTML_LIST_ITEM_END = Regex("</li\\s*>", RegexOption.IGNORE_CASE)
private val HTML_STRONG_START = Regex("<(?:strong|b)\\b[^>]*>", RegexOption.IGNORE_CASE)
private val HTML_STRONG_END = Regex("</(?:strong|b)\\s*>", RegexOption.IGNORE_CASE)
private val HTML_EMPHASIS_START = Regex("<(?:em|i)\\b[^>]*>", RegexOption.IGNORE_CASE)
private val HTML_EMPHASIS_END = Regex("</(?:em|i)\\s*>", RegexOption.IGNORE_CASE)
private val HTML_TAG = Regex("<[^>]+>")
private val MARKDOWN_IMAGE =
    Regex(
        """!\[([^\]\r\n]*)\]\(\s*(?:<([^>\r\n]+)>|([^\s)\"']+))(?:\s+(?:\"([^\"\r\n]*)\"|'([^'\r\n]*)'|\(([^)\r\n]*)\)))?\s*\)(?:\s*\{([^}\r\n]*)\})?"""
    )
private val IMAGE_SIZE_ATTRIBUTE =
    Regex("(?:^|\\s)\\.sn-size-(sm|small|md|medium|lg|large|xl|xlarge)\\b", RegexOption.IGNORE_CASE)
private val IMAGE_SIZE_TITLE =
    Regex("\\bsn:size=(sm|small|md|medium|lg|large|xl|xlarge)\\b", RegexOption.IGNORE_CASE)

/** Relative widths used by the web editor's `sn-size-*` image annotations. */
internal enum class MarkdownImageSize(val widthFraction: Float) {
    SM(0.55f),
    MD(0.7f),
    LG(0.86f),
    XL(1f),
    ;

    companion object {
        fun fromToken(token: String?): MarkdownImageSize? =
            when (token?.lowercase()) {
                "sm",
                "small" -> SM
                "md",
                "medium" -> MD
                "lg",
                "large" -> LG
                "xl",
                "xlarge" -> XL
                else -> null
            }
    }
}

/** An image occurrence in Markdown, including its presentation hint and accessible description. */
internal data class MarkdownImageReference(
    val source: String,
    val altText: String,
    val title: String?,
    val size: MarkdownImageSize = MarkdownImageSize.SM,
)

/**
 * Make content stored by the web editor safe and useful for the native Markdown renderer.
 *
 * Current records are Markdown, but older imports and editor versions can leave HTML wrappers, HTML
 * image elements, or entities such as &nbsp; in the database. The Markdown library does not
 * reliably turn those HTML image nodes into Compose content, so convert the small supported subset
 * before handing it to the renderer. Image destinations remain subject to
 * MarkdownImageTransformer's same-origin resolver.
 */
internal fun normalizeMarkdownContent(content: String): String {
    var normalized = content.replace("\r\n", "\n").replace('\r', '\n')
    normalized =
        HTML_IMAGE.replace(normalized) { match ->
            val attributes = parseHtmlAttributes(match.groups[1]?.value.orEmpty())
            val source = attributes["src"] ?: return@replace ""
            val alt = escapeMarkdownText(attributes["alt"].orEmpty())
            val title = attributes["title"]?.let { " \"${escapeMarkdownText(it)}\"" }.orEmpty()
            "![$alt]($source$title)"
        }
    normalized = HTML_BREAK.replace(normalized, "\n")
    normalized = HTML_PARAGRAPH_END.replace(normalized, "\n\n")
    normalized = HTML_LIST_ITEM_START.replace(normalized, "- ")
    normalized = HTML_LIST_ITEM_END.replace(normalized, "\n")
    normalized = HTML_STRONG_START.replace(normalized, "**")
    normalized = HTML_STRONG_END.replace(normalized, "**")
    normalized = HTML_EMPHASIS_START.replace(normalized, "*")
    normalized = HTML_EMPHASIS_END.replace(normalized, "*")
    normalized = HTML_TAG.replace(normalized, "")
    normalized = decodeHtmlEntities(normalized).replace('\u00A0', ' ')
    normalized = normalizeMarkdownImageAttributes(normalized)

    return normalized
        .lines()
        .joinToString("\n") { it.trimEnd() }
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}

/**
 * Convert the web editor's Pandoc-style image attributes into an invisible title marker understood
 * by the Android image adapter. The Markdown renderer does not know about `{.sn-size-*}` and would
 * otherwise display the marker as literal text after the image.
 */
private fun normalizeMarkdownImageAttributes(content: String): String =
    MARKDOWN_IMAGE.replace(content) { match ->
        val alt = match.groups[1]?.value.orEmpty()
        val source = match.groups[2]?.value ?: match.groups[3]?.value.orEmpty()
        val title = match.groups[4]?.value ?: match.groups[5]?.value ?: match.groups[6]?.value
        val sizeToken =
            match.groups[7]?.value?.let { IMAGE_SIZE_ATTRIBUTE.find(it)?.groupValues?.get(1) }
                ?: title?.let { IMAGE_SIZE_TITLE.find(it)?.groupValues?.get(1) }
        val size = MarkdownImageSize.fromToken(sizeToken)
        val cleanedTitle = title?.replace(IMAGE_SIZE_TITLE, "")?.replace(Regex("\\s+"), " ")?.trim()
        val nextTitle =
            size?.let { "sn:size=${it.name.lowercase()}".let { marker -> listOfNotNull(cleanedTitle, marker).joinToString(" ") } }
                ?: cleanedTitle
        val titlePart = nextTitle?.takeIf(String::isNotBlank)?.let { " \"${escapeMarkdownText(it)}\"" }.orEmpty()
        "![${escapeMarkdownText(alt)}]($source$titlePart)"
    }

/** Extract Markdown image occurrences after [normalizeMarkdownContent] has cleaned their syntax. */
internal fun extractMarkdownImageReferences(content: String): List<MarkdownImageReference> =
    MARKDOWN_IMAGE.findAll(content).map { match ->
        val title = match.groups[4]?.value ?: match.groups[5]?.value ?: match.groups[6]?.value
        MarkdownImageReference(
            source = match.groups[2]?.value ?: match.groups[3]?.value.orEmpty(),
            altText = match.groups[1]?.value.orEmpty(),
            title = title?.replace(IMAGE_SIZE_TITLE, "")?.replace(Regex("\\s+"), " ")?.trim(),
            size = MarkdownImageSize.fromToken(title?.let { IMAGE_SIZE_TITLE.find(it)?.groupValues?.get(1) })
                ?: MarkdownImageSize.SM,
        )
    }.toList()

private fun parseHtmlAttributes(rawAttributes: String): Map<String, String> = buildMap {
    HTML_ATTRIBUTE.findAll(rawAttributes).forEach { match ->
        val name = match.groups[1]?.value?.lowercase() ?: return@forEach
        val value =
            match.groups[2]?.value
                ?: match.groups[3]?.value
                ?: match.groups[4]?.value
                ?: return@forEach
        put(name, decodeHtmlEntities(value))
    }
}

private fun escapeMarkdownText(value: String): String =
    value.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]")
