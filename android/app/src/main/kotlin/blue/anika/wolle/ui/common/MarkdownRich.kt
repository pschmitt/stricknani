@file:OptIn(ExperimentalRichTextApi::class)

package blue.anika.wolle.ui.common

import androidx.compose.ui.text.TextRange
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.document.RichTextBlock
import com.mohamedrejeb.richeditor.document.RichTextBlockType
import com.mohamedrejeb.richeditor.document.RichTextDocument
import com.mohamedrejeb.richeditor.document.RichTextSpanMark
import com.mohamedrejeb.richeditor.model.RichTextState
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser

/**
 * The Markdown <-> rich text model behind the visual editor, the Android counterpart of the web's
 * `markdown-model.ts`. The rich editor library is only the editing model: Markdown is read with its
 * parser (after backslash escapes are turned into literal characters) and written by [toMarkdown],
 * which escapes every character that would otherwise mean something, as prosemirror-markdown does
 * on the web. So typed text such as `5 * 3`, `* k2, p2 *` or `# not a heading` round-trips and no
 * edit is ever refused.
 *
 * What the model cannot carry (tables, quotes, code blocks, HTML, pictures, ...) is detected when a
 * text is loaded: [canEditVisually] converts it to rich text and back and both Markdown strings
 * must render to the same HTML, like the web. Anything else is edited as Markdown ("Raw").
 */
internal object MarkdownRich {
    private val flavour = GFMFlavourDescriptor()
    private val whitespace = Regex("\\s+")

    /** Blocks and inlines the visual editor cannot show without changing them. */
    private val UNSUPPORTED: Set<IElementType> =
        setOf(
            GFMElementTypes.TABLE,
            MarkdownElementTypes.BLOCK_QUOTE,
            MarkdownElementTypes.CODE_FENCE,
            MarkdownElementTypes.CODE_BLOCK,
            MarkdownElementTypes.HTML_BLOCK,
            MarkdownTokenTypes.HTML_TAG,
            MarkdownElementTypes.LINK_DEFINITION,
            MarkdownElementTypes.FULL_REFERENCE_LINK,
            MarkdownElementTypes.SHORT_REFERENCE_LINK,
            MarkdownElementTypes.SETEXT_1,
            MarkdownElementTypes.SETEXT_2,
            MarkdownTokenTypes.HORIZONTAL_RULE,
        )

    /** Parents inside which a picture cannot be an atomic unit of the editor (a linked picture). */
    private val LINKS: Set<IElementType> =
        setOf(
            MarkdownElementTypes.INLINE_LINK,
            MarkdownElementTypes.FULL_REFERENCE_LINK,
            MarkdownElementTypes.SHORT_REFERENCE_LINK,
            MarkdownElementTypes.AUTOLINK,
        )

    /** More pictures than placeholder characters: the text stays in Raw. */
    private const val MAX_IMAGES = 0x800

    private fun parse(source: String): ASTNode =
        MarkdownParser(flavour).buildMarkdownTreeFromString(source)

    private fun hasUnsupported(node: ASTNode, inLink: Boolean = false): Boolean =
        if (node.type == MarkdownElementTypes.IMAGE) {
            // Inline pictures only; what is inside the brackets is the alt text, not content.
            inLink || node.children.none { it.type == MarkdownElementTypes.INLINE_LINK }
        } else {
            node.type in UNSUPPORTED ||
                node.children.any { hasUnsupported(it, inLink || node.type in LINKS) }
        }

    private fun countImages(node: ASTNode): Int =
        (if (node.type == MarkdownElementTypes.IMAGE) 1 else 0) + node.children.sumOf(::countImages)

    /** What the server and the reader render for [markdown], whitespace-insensitive. */
    internal fun renderedHtml(markdown: String, keepLineBreaks: Boolean): String {
        val source = markdownPreviewSource(markdown.replace("\r\n", "\n"), keepLineBreaks)
        val html = HtmlGenerator(source, parse(source), flavour).generateHtml()
        return html.replace(whitespace, " ").replace("> <", "><").trim()
    }

    // --- Loading -----------------------------------------------------------------------------

    /** Backslash-escaped ASCII punctuation becomes one private-use character (same length). */
    private const val PLACEHOLDER_BASE = 0xE000
    private val codeSpanOrEscape = Regex("(`+)[\\s\\S]*?\\1|\\\\([!-/:-@\\[-`{-~])|(=)")

    private fun placeholder(c: Char) = (PLACEHOLDER_BASE + c.code).toChar()

    private fun isPlaceholder(c: Char) =
        c.code in (PLACEHOLDER_BASE + 0x21)..(PLACEHOLDER_BASE + 0x7e)

    private fun restore(text: String): String =
        if (text.none(::isPlaceholder)) text
        else
            buildString {
                for (c in text) append(
                    if (isPlaceholder(c)) (c.code - PLACEHOLDER_BASE).toChar() else c
                )
            }

    /**
     * Put a backslash before `*`, `_` and `~` that are not emphasis delimiters, list bullets or
     * part of a link target or code span, so they stay literal text. (The library would otherwise
     * reshuffle the spaces around them.)
     */
    private fun escapeStrayDelimiters(markdown: String): String {
        val tree = parse(markdown)
        val real = HashSet<Int>()
        val skipped = mutableListOf<IntRange>()
        val delimiterParents =
            setOf(
                MarkdownElementTypes.EMPH,
                MarkdownElementTypes.STRONG,
                GFMElementTypes.STRIKETHROUGH,
            )
        val opaque =
            setOf(
                MarkdownElementTypes.CODE_SPAN,
                MarkdownElementTypes.LINK_DESTINATION,
                MarkdownElementTypes.AUTOLINK,
                GFMTokenTypes.GFM_AUTOLINK,
                MarkdownTokenTypes.URL,
                MarkdownTokenTypes.EMAIL_AUTOLINK,
                MarkdownTokenTypes.LIST_BULLET,
            )

        fun walk(node: ASTNode) {
            if (node.type in opaque) {
                skipped.add(node.startOffset until node.endOffset)
                return
            }
            if (node.type in delimiterParents) {
                node.children
                    .filter { it.type == MarkdownTokenTypes.EMPH || it.type == GFMTokenTypes.TILDE }
                    .forEach { for (i in it.startOffset until it.endOffset) real.add(i) }
            }
            node.children.forEach(::walk)
        }
        walk(tree)
        val out = StringBuilder()
        var backslashes = 0
        for ((i, c) in markdown.withIndex()) {
            val delimiter = c == '*' || c == '_' || c == '~'
            if (delimiter && backslashes % 2 == 0 && i !in real && skipped.none { i in it }) {
                out.append('\\')
            }
            backslashes = if (c == '\\') backslashes + 1 else 0
            out.append(c)
        }
        return out.toString()
    }

    // --- Pictures ----------------------------------------------------------------------------

    /** One private-use character per picture while the library parses; see [extractImages]. */
    private const val IMAGE_BASE = 0xF000

    private fun isImagePlaceholder(c: Char) = c.code in IMAGE_BASE until IMAGE_BASE + MAX_IMAGES

    private val imageAttributes = Regex("^\\{[^}\\r\\n]*\\}")

    /** The Markdown with every picture replaced by one placeholder character, and the pictures. */
    internal class Extracted(val source: String, val images: List<EditorImage>)

    /**
     * Pictures are atomic in the editor and written back exactly as they were read (including the
     * web's `{.sn-size-*}` attribute), so the library never sees them: each one becomes a single
     * placeholder character that [restoreLiterals] turns into an image span.
     */
    internal fun extractImages(source: String): Extracted {
        val ranges = mutableListOf<IntRange>()
        val alts = mutableListOf<String>()

        fun walk(node: ASTNode, inLink: Boolean) {
            if (node.type == MarkdownElementTypes.IMAGE && !inLink) {
                var end = node.endOffset
                imageAttributes.find(source.substring(end))?.let { end += it.value.length }
                ranges.add(node.startOffset until end)
                val label =
                    node.children
                        .firstOrNull { it.type == MarkdownElementTypes.INLINE_LINK }
                        ?.children
                        ?.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
                alts.add(
                    label?.let { source.substring(it.startOffset + 1, it.endOffset - 1) }.orEmpty()
                )
                return
            }
            node.children.forEach { walk(it, inLink || node.type in LINKS) }
        }
        walk(parse(source), false)
        if (ranges.isEmpty() || ranges.size > MAX_IMAGES) return Extracted(source, emptyList())
        val out = StringBuilder()
        val images = mutableListOf<EditorImage>()
        var at = 0
        for ((i, range) in ranges.withIndex()) {
            if (range.first < at) continue
            out.append(source, at, range.first)
            out.append((IMAGE_BASE + images.size).toChar())
            images.add(EditorImage(source.substring(range), EditorImage.unescapeAlt(alts[i])))
            at = range.last + 1
        }
        out.append(source, at, source.length)
        return Extracted(out.toString(), images)
    }

    private fun prepare(markdown: String): Extracted {
        val extracted = extractImages(markdown.replace("\r\n", "\n"))
        val escaped = escapeStrayDelimiters(extracted.source)
        val source =
            codeSpanOrEscape.replace(escaped) { match ->
                // The library drops a bare `=` (a setext token) from text, so it is carried too.
                val literal = match.groups[2] ?: match.groups[3]
                if (literal == null) match.value else placeholder(literal.value[0]).toString()
            }
        return Extracted(source, extracted.images)
    }

    private fun restoreLiterals(state: RichTextState, images: List<EditorImage>) {
        val document = state.toRichTextDocument()
        val changed =
            document.blocks.any { block ->
                block.text.any { isPlaceholder(it) || isImagePlaceholder(it) } ||
                    block.spans.any { it is RichTextSpanMark.Link && it.url.any(::isPlaceholder) }
            }
        if (!changed) return
        val blocks =
            document.blocks.map { block ->
                // A picture is a visible, atomic chip (see [EditorImage.chip]); its text is longer
                // than the placeholder, so the ranges of the other marks move with it.
                val text = restore(block.text)
                val starts = IntArray(text.length + 1)
                val out = StringBuilder()
                val pictures = mutableListOf<RichTextSpanMark>()
                for (i in text.indices) {
                    starts[i] = out.length
                    val image =
                        if (isImagePlaceholder(text[i])) images.getOrNull(text[i].code - IMAGE_BASE)
                        else null
                    if (image == null) {
                        out.append(text[i])
                    } else {
                        val chip = image.chip()
                        pictures.add(
                            RichTextSpanMark.Token(
                                out.length until out.length + chip.length,
                                EditorImage.TRIGGER,
                                image.markdown,
                                chip,
                            )
                        )
                        out.append(chip)
                    }
                }
                starts[text.length] = out.length
                fun moved(range: IntRange) =
                    starts.getOrElse(range.first) { out.length }..(starts.getOrElse(
                            range.last + 1
                        ) {
                            out.length
                        } - 1)
                block.copy(
                    text = out.toString(),
                    spans =
                        block.spans.map { mark ->
                            when (mark) {
                                is RichTextSpanMark.Link ->
                                    mark.copy(url = restore(mark.url), range = moved(mark.range))
                                is RichTextSpanMark.Bold -> mark.copy(range = moved(mark.range))
                                is RichTextSpanMark.Italic -> mark.copy(range = moved(mark.range))
                                is RichTextSpanMark.Strikethrough ->
                                    mark.copy(range = moved(mark.range))
                                is RichTextSpanMark.CodeSpan -> mark.copy(range = moved(mark.range))
                                else -> mark
                            }
                        } + pictures,
                )
            }
        state.setRichTextDocument(RichTextDocument(blocks))
    }

    /** Replace the content of [state] with [markdown]. */
    fun load(state: RichTextState, markdown: String) {
        val prepared = prepare(markdown)
        state.setMarkdown(prepared.source)
        restoreLiterals(state, prepared.images)
    }

    /** A rich text state holding [markdown]. */
    fun toRich(markdown: String): RichTextState = RichTextState().also { load(it, markdown) }

    /**
     * Pictures are one unit: the library lets Backspace eat a chip character by character, so a
     * chip whose text no longer matches its label (part of it deleted or typed into) is removed
     * whole, like deleting one character. Returns true when the state was changed.
     */
    fun dropDamagedPictures(state: RichTextState): Boolean {
        val document = state.toRichTextDocument()
        var damaged = false
        val blocks =
            document.blocks.map { block ->
                val bad =
                    block.spans
                        .filterIsInstance<RichTextSpanMark.Token>()
                        .filter { it.trigger == EditorImage.TRIGGER }
                        .filter { token ->
                            val range = token.range
                            range.first < 0 ||
                                range.last >= block.text.length ||
                                block.text.substring(range.first, range.last + 1) != token.label
                        }
                if (bad.isEmpty()) return@map block
                damaged = true
                val cut = bad.map {
                    it.range.first.coerceAtLeast(0)..it.range.last.coerceAtMost(
                            block.text.lastIndex
                        )
                }
                fun moved(index: Int) =
                    index - cut.sumOf { r -> (minOf(r.last + 1, index) - r.first).coerceAtLeast(0) }
                fun shifted(range: IntRange) = moved(range.first)..(moved(range.last + 1) - 1)
                block.copy(
                    text = block.text.filterIndexed { i, _ -> cut.none { i in it } },
                    spans =
                        block.spans
                            .filterNot { it in bad }
                            .map { mark ->
                                when (mark) {
                                    is RichTextSpanMark.Link ->
                                        mark.copy(range = shifted(mark.range))
                                    is RichTextSpanMark.Bold ->
                                        mark.copy(range = shifted(mark.range))
                                    is RichTextSpanMark.Italic ->
                                        mark.copy(range = shifted(mark.range))
                                    is RichTextSpanMark.Strikethrough ->
                                        mark.copy(range = shifted(mark.range))
                                    is RichTextSpanMark.CodeSpan ->
                                        mark.copy(range = shifted(mark.range))
                                    is RichTextSpanMark.Token ->
                                        mark.copy(range = shifted(mark.range))
                                    else -> mark
                                }
                            },
                )
            }
        if (!damaged) return false
        val before = state.annotatedString.text
        val selection = state.selection
        state.setRichTextDocument(RichTextDocument(blocks))
        val after = state.annotatedString.text
        // Keep the caret where the removed text was.
        var prefix = 0
        while (prefix < after.length && prefix < before.length && before[prefix] == after[prefix]) {
            prefix++
        }
        val removed = before.length - after.length
        fun place(offset: Int) =
            when {
                offset <= prefix -> offset
                offset >= prefix + removed -> offset - removed
                else -> prefix
            }.coerceIn(0, after.length)
        state.selection = TextRange(place(selection.start), place(selection.end))
        return true
    }

    // --- Writing -----------------------------------------------------------------------------

    /** The Markdown of [state], without trailing blank lines. */
    fun toMarkdown(state: RichTextState, keepLineBreaks: Boolean = false): String =
        MarkdownWriter.write(state.toRichTextDocument(), keepLineBreaks)

    // --- Guard -------------------------------------------------------------------------------

    /**
     * The Markdown the visual editor would write for [markdown] when nothing was edited, or null
     * when the text cannot be edited visually (it is unsupported or the round trip changes how it
     * renders). Blank text is always editable.
     */
    fun roundTrip(markdown: String, keepLineBreaks: Boolean): String? {
        if (markdown.isBlank()) return ""
        return try {
            val tree = parse(markdown)
            if (hasUnsupported(tree) || countImages(tree) > MAX_IMAGES) return null
            val again = toMarkdown(toRich(markdown), keepLineBreaks)
            if (again.isBlank() || hasUnsupported(parse(again))) return null
            again.takeIf {
                renderedHtml(markdown, keepLineBreaks) == renderedHtml(it, keepLineBreaks)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun canEditVisually(markdown: String, keepLineBreaks: Boolean): Boolean =
        roundTrip(markdown, keepLineBreaks) != null
}

/** Rich text document to Markdown: `-` bullets, `*em*`, `**strong**`, everything else escaped. */
internal object MarkdownWriter {
    private sealed interface Token {
        data object Bold : Token

        data object Italic : Token

        data object Strike : Token

        data object Code : Token

        data class Link(val url: String) : Token
    }

    fun write(document: RichTextDocument, keepLineBreaks: Boolean): String {
        val lines = mutableListOf<String>()
        // Marker widths of the open list items, by level, and ordered counters.
        val widths = mutableListOf<Int>()
        val counters = mutableListOf<Int>()
        var previous: RichTextBlock? = null
        for (block in document.blocks) {
            val type = block.type
            val body = inline(block.text, block.spans)
            val isEmpty = body.isEmpty()
            val prefix: String
            if (type is RichTextBlockType.ListItem) {
                val level = type.indent
                while (widths.size > level + 1) widths.removeAt(widths.lastIndex)
                while (counters.size > level + 1) counters.removeAt(counters.lastIndex)
                while (widths.size < level + 1) widths.add(2)
                while (counters.size < level + 1) counters.add(0)
                val marker =
                    if (type.ordered) {
                        val number = type.startNumber ?: (counters[level] + 1)
                        counters[level] = number
                        "$number."
                    } else {
                        counters[level] = 0
                        "-"
                    }
                widths[level] = marker.length + 1
                prefix = " ".repeat(widths.take(level).sum()) + marker + if (isEmpty) "" else " "
            } else {
                widths.clear()
                counters.clear()
                prefix =
                    if (block.headingLevel in 1..6 && !isEmpty) "#".repeat(block.headingLevel) + " "
                    else ""
            }
            val line = prefix + body
            val prev = previous
            if (prev != null && lines.isNotEmpty()) {
                val prevList = prev.type is RichTextBlockType.ListItem
                val thisList = type is RichTextBlockType.ListItem
                val prevBlank = prev.text.isBlank()
                val needsBlank =
                    !prevBlank &&
                        !isEmpty &&
                        ((!prevList && thisList) ||
                            (prevList && !thisList) ||
                            (!keepLineBreaks &&
                                !prevList &&
                                !thisList &&
                                prev.headingLevel == 0 &&
                                block.headingLevel == 0))
                if (needsBlank) lines.add("")
            }
            lines.add(line)
            previous = block
        }
        return lines.joinToString("\n").trimEnd()
    }

    private fun inline(rawText: String, spans: List<RichTextSpanMark>): String {
        val text = rawText.replace('\n', ' ')
        var start = 0
        var end = text.length
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        if (start >= end) return ""

        val bold = BooleanArray(text.length)
        val italic = BooleanArray(text.length)
        val strike = BooleanArray(text.length)
        val code = BooleanArray(text.length)
        val links = arrayOfNulls<String>(text.length)
        val images = arrayOfNulls<String>(text.length)
        fun fill(flags: BooleanArray, range: IntRange) {
            for (i in range) if (i in text.indices) flags[i] = true
        }
        for (mark in spans) {
            when (mark) {
                is RichTextSpanMark.Bold -> fill(bold, mark.range)
                is RichTextSpanMark.Italic -> fill(italic, mark.range)
                is RichTextSpanMark.Strikethrough -> fill(strike, mark.range)
                is RichTextSpanMark.CodeSpan -> fill(code, mark.range)
                is RichTextSpanMark.Link ->
                    for (i in mark.range) if (i in text.indices) links[i] = mark.url
                is RichTextSpanMark.Image ->
                    for (i in mark.range) if (i in text.indices) {
                        images[i] = EditorImage.markdownOf(mark.url, mark.description)
                    }
                is RichTextSpanMark.Token ->
                    if (mark.trigger == EditorImage.TRIGGER) {
                        // The chip stands for the original picture Markdown, written once.
                        for (i in mark.range) if (i in text.indices) {
                            images[i] = if (i == mark.range.first) mark.id else ""
                        }
                    }
                else -> Unit
            }
        }
        // Emphasis cannot start or end with a space: the spaces move outside the markers.
        for (flags in listOf(bold, italic, strike, code)) trimEdges(text, flags)
        val linkKeys = links.filterNotNull().distinct()
        for (url in linkKeys) {
            val flags = BooleanArray(text.length) { links[it] == url }
            trimEdges(text, flags)
            for (i in text.indices) if (links[i] == url && !flags[i]) links[i] = null
        }

        val out = StringBuilder()
        var open = emptyList<Token>()
        var i = start
        while (i < end) {
            val wanted = buildList {
                links[i]?.let { add(Token.Link(it)) }
                if (bold[i]) add(Token.Bold)
                if (italic[i]) add(Token.Italic)
                if (strike[i]) add(Token.Strike)
                if (code[i]) add(Token.Code)
            }
            var common = 0
            while (common < open.size && common < wanted.size && open[common] == wanted[common]) {
                common++
            }
            for (k in open.lastIndex downTo common) out.append(close(open[k]))
            for (k in common until wanted.size) out.append(open(wanted[k]))
            open = wanted
            var j = i + 1
            while (
                j < end &&
                    bold[j] == bold[i] &&
                    italic[j] == italic[i] &&
                    strike[j] == strike[i] &&
                    code[j] == code[i] &&
                    links[j] == links[i] &&
                    images[i] == null &&
                    images[j] == null
            ) {
                j++
            }
            val run = text.substring(i, j)
            val picture = images[i]
            out.append(
                if (picture != null) picture
                else if (code[i]) run else escape(run, atLineStart = out.isEmpty())
            )
            i = j
        }
        for (k in open.lastIndex downTo 0) out.append(close(open[k]))
        return out.toString()
    }

    private fun trimEdges(text: String, flags: BooleanArray) {
        var i = 0
        while (i < flags.size) {
            if (!flags[i]) {
                i++
                continue
            }
            var j = i
            while (j < flags.size && flags[j]) j++
            var a = i
            var b = j
            while (a < b && text[a].isWhitespace()) flags[a++] = false
            while (b > a && text[b - 1].isWhitespace()) flags[--b] = false
            i = j
        }
    }

    private fun open(token: Token): String =
        when (token) {
            Token.Bold -> "**"
            Token.Italic -> "*"
            Token.Strike -> "~~"
            Token.Code -> "`"
            is Token.Link -> "["
        }

    private fun close(token: Token): String =
        when (token) {
            is Token.Link -> "](${destination(token.url)})"
            else -> open(token)
        }

    private fun destination(url: String): String =
        url.replace(" ", "%20").replace("(", "%28").replace(")", "%29")

    private val entityAhead = Regex("^&(#[0-9]+|#[xX][0-9a-fA-F]+|[A-Za-z][A-Za-z0-9]*);")
    private val orderedMarker = Regex("^(\\d{1,9})([.)])(\\s|$)")

    /** Escape what would mean Markdown, like prosemirror-markdown's `esc`. */
    fun escape(text: String, atLineStart: Boolean): String {
        val sb = StringBuilder(text.length + 4)
        for ((index, c) in text.withIndex()) {
            when {
                c == '\\' || c == '`' || c == '*' || c == '_' || c == '[' || c == ']' || c == '~' ->
                    sb.append('\\').append(c)
                c == '<' &&
                    index + 1 < text.length &&
                    (text[index + 1].isLetter() || text[index + 1] in "/!?") -> sb.append("\\<")
                c == '&' && entityAhead.containsMatchIn(text.substring(index)) -> sb.append("\\&")
                else -> sb.append(c)
            }
        }
        if (!atLineStart) return sb.toString()
        val escaped = sb.toString()
        return when {
            Regex("^#{1,6}(\\s|$)").containsMatchIn(escaped) -> "\\$escaped"
            Regex("^[>+]").containsMatchIn(escaped) -> "\\$escaped"
            Regex("^-+(\\s|$)").containsMatchIn(escaped) -> "\\$escaped"
            Regex("^=+\\s*$").containsMatchIn(escaped) -> "\\$escaped"
            Regex("^-{3,}\\s*$").containsMatchIn(escaped) -> "\\$escaped"
            else ->
                orderedMarker.replace(escaped) {
                    "${it.groupValues[1]}\\${it.groupValues[2]}${it.groupValues[3]}"
                }
        }
    }
}
