package blue.anika.wolle.data.util

private val HTML_ENTITY = Regex("&(?:#(\\d+)|#x([0-9a-fA-F]+)|([A-Za-z][A-Za-z0-9]+));")

private val NAMED_ENTITIES =
    mapOf(
        "nbsp" to " ",
        "amp" to "&",
        "quot" to "\"",
        "apos" to "'",
        "lt" to "<",
        "gt" to ">",
        "copy" to "©",
        "reg" to "®",
        "trade" to "™",
        "hellip" to "…",
        "ndash" to "–",
        "mdash" to "—",
        "bull" to "•",
        "middot" to "·",
        "times" to "×",
        "divide" to "÷",
        "plusmn" to "±",
        "euro" to "€",
        "pound" to "£",
        "yen" to "¥",
        "cent" to "¢",
    )

/** Decode the HTML entities that can occur in imported Markdown/HTML content. */
internal fun decodeHtmlEntities(value: String): String =
    HTML_ENTITY.replace(value) { match ->
        val decoded =
            when {
                match.groups[1] != null ->
                    match.groups[1]!!.value.toIntOrNull()?.let(::codePointToString)
                match.groups[2] != null ->
                    match.groups[2]!!.value.toIntOrNull(16)?.let(::codePointToString)
                else -> NAMED_ENTITIES[match.groups[3]!!.value.lowercase()]
            }
        decoded ?: match.value
    }

private fun codePointToString(codePoint: Int): String? =
    if (Character.isValidCodePoint(codePoint)) {
        String(Character.toChars(codePoint))
    } else {
        null
    }
