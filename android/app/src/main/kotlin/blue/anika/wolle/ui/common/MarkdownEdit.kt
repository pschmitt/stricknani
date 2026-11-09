package blue.anika.wolle.ui.common

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** The formatting actions of the Markdown toolbar. */
internal enum class MarkdownFormat {
    BOLD,
    ITALIC,
    HEADING,
    BULLET_LIST,
    NUMBERED_LIST,
    LINK,
}

/** Heading level the toolbar writes, the same as the web editor. */
private const val HEADING_PREFIX = "### "
private const val LINK_PLACEHOLDER = "https://"

private val HEADING_RE = Regex("^#{1,6}\\s+")
private val BULLET_RE = Regex("^[-*+]\\s+")
private val NUMBERED_RE = Regex("^\\d+\\.\\s+")

/** Insert or toggle Markdown around the selection of [value]; the selection follows the text. */
internal fun applyMarkdownFormat(value: TextFieldValue, format: MarkdownFormat): TextFieldValue =
    when (format) {
        MarkdownFormat.BOLD -> toggleWrap(value, "**")
        MarkdownFormat.ITALIC -> toggleWrap(value, "*")
        MarkdownFormat.HEADING -> toggleLinePrefix(value, LinePrefix.HEADING)
        MarkdownFormat.BULLET_LIST -> toggleLinePrefix(value, LinePrefix.BULLET)
        MarkdownFormat.NUMBERED_LIST -> toggleLinePrefix(value, LinePrefix.NUMBERED)
        MarkdownFormat.LINK -> insertLink(value)
    }

/**
 * Source as the server renders it. A list-like field (other materials) keeps every line break;
 * prose fields join soft-wrapped lines. Used to compare what a text renders to.
 */
internal fun markdownPreviewSource(text: String, keepLineBreaks: Boolean): String =
    if (keepLineBreaks) text.replace(Regex("(?<!\n)\n(?!\n)"), "  \n") else text

private fun isWrapped(text: String, start: Int, end: Int, marker: String): Boolean {
    val n = marker.length
    if (start < n || end + n > text.length) return false
    if (!text.regionMatches(start - n, marker, 0, n) || !text.regionMatches(end, marker, 0, n)) {
        return false
    }
    // A single * next to another * is the edge of a bold run, not italic.
    if (marker == "*") {
        if (start >= 2 && text[start - 2] == '*') return false
        if (end + 1 < text.length && text[end + 1] == '*') return false
    }
    return true
}

private fun toggleWrap(value: TextFieldValue, marker: String): TextFieldValue {
    val text = value.text
    val start = minOf(value.selection.start, value.selection.end)
    val end = maxOf(value.selection.start, value.selection.end)
    val n = marker.length
    if (isWrapped(text, start, end, marker)) {
        val out = text.removeRange(end, end + n).removeRange(start - n, start)
        return TextFieldValue(out, TextRange(start - n, end - n))
    }
    val selected = text.substring(start, end)
    if (
        selected.length >= 2 * n &&
            selected.startsWith(marker) &&
            selected.endsWith(marker) &&
            !(marker == "*" && selected.startsWith("**"))
    ) {
        val inner = selected.substring(n, selected.length - n)
        return TextFieldValue(
            text.replaceRange(start, end, inner),
            TextRange(start, start + inner.length),
        )
    }
    val out = text.substring(0, start) + marker + selected + marker + text.substring(end)
    return TextFieldValue(out, TextRange(start + n, end + n))
}

private enum class LinePrefix(val regex: Regex) {
    HEADING(Regex("^### ")),
    BULLET(BULLET_RE),
    NUMBERED(NUMBERED_RE),
}

private fun stripBlockPrefix(line: String): String =
    line.replace(HEADING_RE, "").replace(BULLET_RE, "").replace(NUMBERED_RE, "")

private fun toggleLinePrefix(value: TextFieldValue, kind: LinePrefix): TextFieldValue {
    val text = value.text
    val start = minOf(value.selection.start, value.selection.end)
    var end = maxOf(value.selection.start, value.selection.end)
    if (end > start && text[end - 1] == '\n') end -= 1
    val lineStart = text.lastIndexOf('\n', start - 1) + 1
    val lineEnd = text.indexOf('\n', end).let { if (it == -1) text.length else it }
    val lines = text.substring(lineStart, lineEnd).split("\n")
    val filled = lines.filter { it.isNotBlank() }
    val remove = filled.isNotEmpty() && filled.all { kind.regex.containsMatchIn(it) }
    var number = 0
    val rewritten = lines.map { line ->
        if (line.isBlank() && lines.size > 1) {
            line
        } else if (remove) {
            line.replace(kind.regex, "")
        } else {
            val bare = stripBlockPrefix(line)
            when (kind) {
                LinePrefix.HEADING -> HEADING_PREFIX + bare
                LinePrefix.BULLET -> "- $bare"
                LinePrefix.NUMBERED -> "${++number}. $bare"
            }
        }
    }
    val joined = rewritten.joinToString("\n")
    val out = text.substring(0, lineStart) + joined + text.substring(lineEnd)
    val firstDelta = rewritten.first().length - lines.first().length
    val totalDelta = joined.length - (lineEnd - lineStart)
    val newStart = maxOf(lineStart, start + firstDelta)
    val newEnd = maxOf(newStart, end + totalDelta)
    return TextFieldValue(out, TextRange(newStart, newEnd))
}

private fun insertLink(value: TextFieldValue): TextFieldValue {
    val text = value.text
    val start = minOf(value.selection.start, value.selection.end)
    val end = maxOf(value.selection.start, value.selection.end)
    val selected = text.substring(start, end)
    val before = text.substring(0, start)
    val after = text.substring(end)
    return when {
        selected.isEmpty() ->
            TextFieldValue("$before[]($LINK_PLACEHOLDER)$after", TextRange(start + 1))
        selected.startsWith("http://") || selected.startsWith("https://") ->
            TextFieldValue("$before[]($selected)$after", TextRange(start + 1))
        else -> {
            val urlStart = start + selected.length + 3
            TextFieldValue(
                "$before[$selected]($LINK_PLACEHOLDER)$after",
                TextRange(urlStart, urlStart + LINK_PLACEHOLDER.length),
            )
        }
    }
}
