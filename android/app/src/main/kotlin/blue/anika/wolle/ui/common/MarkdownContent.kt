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

/**
 * Make content stored by the web editor safe and useful for the native Markdown renderer.
 *
 * Current records are Markdown, but older imports and editor versions can leave HTML wrappers,
 * HTML image elements, or entities such as &nbsp; in the database. The Markdown library does
 * not reliably turn those HTML image nodes into Compose content, so convert the small supported
 * subset before handing it to the renderer. Image destinations remain subject to
 * MarkdownImageTransformer's same-origin resolver.
 */
internal fun normalizeMarkdownContent(content: String): String {
    var normalized = content.replace("\r\n", "\n").replace('\r', '\n')
    normalized =
        HTML_IMAGE.replace(normalized) { match ->
            val attributes = parseHtmlAttributes(match.groups[1]?.value.orEmpty())
            val source = attributes["src"] ?: return@replace ""
            val alt = escapeMarkdownText(attributes["alt"].orEmpty())
            val title =
                attributes["title"]?.let { " \"${escapeMarkdownText(it)}\"" }.orEmpty()
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

    return normalized
        .lines()
        .joinToString("\n") { it.trimEnd() }
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}

private fun parseHtmlAttributes(rawAttributes: String): Map<String, String> =
    buildMap {
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
