package blue.anika.wolle.ui.common

/**
 * A picture of the visual editor: the Markdown exactly as it was read (`![alt](url){.sn-size-md}`,
 * written back verbatim so nothing about it changes) and its alt text for display.
 *
 * Compose Rich Editor 1.2.1 cannot show inline images in its editable text field (only its
 * read-only `RichText` renders inline content), so a picture is an atomic token chip in the text: a
 * picture symbol and the alt text. Backspace or a selection removes it as one character, text can
 * be typed before and after it, and it is written back as [markdown].
 */
internal data class EditorImage(val markdown: String, val alt: String) {
    /** The destination as written in [markdown] (`/media/...`, absolute), or null if unparsable. */
    val source: String?
        get() = parse(markdown)?.second

    /** [source] resolved against the configured server, null when it is unsafe or unusable. */
    fun resolvedSource(resolveUrl: (String?) -> String?): String? = source?.let(resolveUrl)

    /** The visible text of the chip. */
    fun chip(): String {
        val label = alt.replace(Regex("\\s+"), " ").trim()
        val short =
            if (label.length > CHIP_LABEL_MAX) label.take(CHIP_LABEL_MAX - 1) + "…" else label
        return if (short.isEmpty()) SYMBOL else "$SYMBOL $short"
    }

    companion object {
        /** The token trigger that marks a picture chip. */
        const val TRIGGER = "sn-image"

        /** Framed picture (U+1F5BC). */
        private const val SYMBOL = "🖼"
        private const val CHIP_LABEL_MAX = 40
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
