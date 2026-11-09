package blue.anika.wolle.ui.common

import com.mohamedrejeb.richeditor.model.RichTextState
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser

/**
 * The Markdown <-> rich text model behind the visual editor, the Android counterpart of the web's
 * `markdown-model.ts`. The rich editor library reads and writes Markdown through its own mapping,
 * which does not carry everything (tables, quotes, code blocks, HTML, images, ...). Like the web,
 * [canEditVisually] proves that a text survives the round trip before the visual editor may open
 * it: the text is converted to rich text and back, and both Markdown strings must render to the
 * same HTML. Anything else is edited as Markdown ("Raw"), so nothing is mangled.
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
            // The editor cannot display (or resolve `/media/...`) pictures: keep them in Raw.
            MarkdownElementTypes.IMAGE,
        )

    private fun parse(source: String): ASTNode =
        MarkdownParser(flavour).buildMarkdownTreeFromString(source)

    private fun hasUnsupported(node: ASTNode): Boolean =
        node.type in UNSUPPORTED || node.children.any(::hasUnsupported)

    /** What the server and the reader render for [markdown], whitespace-insensitive. */
    internal fun renderedHtml(markdown: String, keepLineBreaks: Boolean): String {
        val source = markdownPreviewSource(markdown.replace("\r\n", "\n"), keepLineBreaks)
        val html = HtmlGenerator(source, parse(source), flavour).generateHtml()
        return html.replace(whitespace, " ").replace("> <", "><").trim()
    }

    /** A rich text state holding [markdown]. */
    fun toRich(markdown: String): RichTextState =
        RichTextState().setMarkdown(markdown.replace("\r\n", "\n"))

    /** Replace the content of [state] with [markdown]. */
    fun load(state: RichTextState, markdown: String) {
        state.setMarkdown(markdown.replace("\r\n", "\n"))
    }

    private fun plain(text: String): String = text.replace(whitespace, " ").trim()

    /**
     * True when [markdown], written for the edited [state], reads back as the same text. The
     * library does not escape characters that mean something in Markdown (`*`, `_`, `#`, `[`), so a
     * typed `5 * 3` would come back changed; such an edit must not be saved.
     */
    fun survivesEdit(state: RichTextState, markdown: String): Boolean =
        try {
            plain(toRich(markdown).toText()) == plain(state.toText())
        } catch (_: Exception) {
            false
        }

    /** The Markdown of [state], without trailing blank lines. */
    fun toMarkdown(state: RichTextState): String = state.toMarkdown().trimEnd()

    /**
     * The Markdown the visual editor would write for [markdown] when nothing was edited, or null
     * when the text cannot be edited visually (it is unsupported or the round trip changes how it
     * renders). Blank text is always editable.
     */
    fun roundTrip(markdown: String, keepLineBreaks: Boolean): String? {
        if (markdown.isBlank()) return ""
        return try {
            if (hasUnsupported(parse(markdown))) return null
            val again = toMarkdown(toRich(markdown))
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
