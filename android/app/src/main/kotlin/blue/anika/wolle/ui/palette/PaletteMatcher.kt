package blue.anika.wolle.ui.palette

import java.net.URI
import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min

/**
 * Matching and ranking behind the command palette: the same rules as the website's
 * `web/src/lib/palette/match.ts`, keep both in step.
 *
 * Folding keeps the text length: every character becomes exactly one character, lower case and
 * without accents (a, o, u for the umlauts; s for the sharp s), so a match position in the folded
 * text is a position in the original.
 */
object PaletteMatcher {
    /** Rows shown per group before "Show more". */
    const val GROUP_LIMIT = 8

    private const val EXACT = 1000.0
    private const val PREFIX = 800.0
    private const val WORD = 600.0
    private const val SUBSTRING = 400.0

    enum class Kind {
        Exact,
        Prefix,
        Word,
        Substring,
        Fuzzy,
    }

    /** Half-open [start, end) range of the original text. */
    data class Range(val start: Int, val end: Int)

    data class WordMatch(val score: Double, val kind: Kind, val ranges: List<Range>)

    /** A searched text and how much a hit in it counts (title 1, details less). */
    data class Field(val text: String, val weight: Double)

    data class Scored(val score: Double, val ranges: Map<Int, List<Range>>)

    fun foldChar(char: Char): Char {
        if (char == 'ß') return 's'
        val first = Normalizer.normalize(char.toString(), Normalizer.Form.NFD).firstOrNull() ?: char
        return first.lowercaseChar()
    }

    fun fold(text: String): String {
        val out = StringBuilder(text.length)
        for (char in text) {
            // Surrogates stay as they are: the length is preserved.
            out.append(if (char.isSurrogate()) char else foldChar(char))
        }
        return out.toString()
    }

    fun queryWords(query: String): List<String> =
        fold(query).split(Regex("\\s+")).filter { it.isNotEmpty() }

    private fun isBoundary(char: Char): Boolean = char.isWhitespace() || char in "-_/.:@,·"

    /** Where one folded word matches in one folded text, or null. */
    fun matchWord(word: String, text: String): WordMatch? {
        if (word.isEmpty() || text.isEmpty()) return null
        if (text == word) return WordMatch(EXACT, Kind.Exact, listOf(Range(0, text.length)))
        if (text.startsWith(word))
            return WordMatch(PREFIX, Kind.Prefix, listOf(Range(0, word.length)))
        var at = text.indexOf(word)
        var substringAt = -1
        while (at >= 0) {
            if (at > 0 && isBoundary(text[at - 1])) {
                return WordMatch(
                    WORD - min(at, 40) / 4.0,
                    Kind.Word,
                    listOf(Range(at, at + word.length)),
                )
            }
            if (substringAt < 0) substringAt = at
            at = text.indexOf(word, at + 1)
        }
        if (substringAt >= 0) {
            return WordMatch(
                SUBSTRING - min(substringAt, 40) / 4.0,
                Kind.Substring,
                listOf(Range(substringAt, substringAt + word.length)),
            )
        }
        if (word.length < 3) return null
        // Fuzzy: the letters in order, with gaps; tighter is better.
        val ranges = ArrayList<Range>()
        var position = 0
        var gaps = 0
        var previous = -1
        for (char in word) {
            val found = text.indexOf(char, position)
            if (found < 0) return null
            if (previous >= 0) gaps += found - previous - 1
            ranges += Range(found, found + 1)
            previous = found
            position = found + 1
        }
        if (gaps > word.length * 3) return null
        return WordMatch(max(50.0, 250.0 - gaps * 8), Kind.Fuzzy, ranges)
    }

    /** Every word must be found in some field; the best field counts, weighted. */
    fun scoreEntry(words: List<String>, fields: List<Field>): Scored? {
        if (words.isEmpty()) return Scored(0.0, emptyMap())
        val folded = fields.map { fold(it.text) }
        var total = 0.0
        val ranges = HashMap<Int, List<Range>>()
        for (word in words) {
            var best = -1.0
            var bestField = -1
            var bestRanges: List<Range> = emptyList()
            folded.forEachIndexed { index, text ->
                val hit = matchWord(word, text) ?: return@forEachIndexed
                val score = hit.score * fields[index].weight
                if (score > best) {
                    best = score
                    bestField = index
                    bestRanges = hit.ranges
                }
            }
            if (best < 0) return null
            total += best
            ranges[bestField] = ranges[bestField].orEmpty() + bestRanges
        }
        return Scored(total, ranges)
    }

    /** Sorted, merged ranges. */
    fun mergeRanges(ranges: List<Range>): List<Range> {
        val out = ArrayList<Range>()
        for (range in ranges.sortedWith(compareBy({ it.start }, { it.end }))) {
            val last = out.lastOrNull()
            if (last != null && range.start <= last.end) {
                out[out.size - 1] = Range(last.start, max(last.end, range.end))
            } else {
                out += range
            }
        }
        return out
    }

    /** The http(s) link a query is (typed or pasted, nothing else in it), or null. */
    fun detectUrl(query: String): String? {
        val text = query.trim()
        if (text.isEmpty() || text.any { it.isWhitespace() }) return null
        if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(text)) return null
        return try {
            val host = URI(text).host
            if (host.isNullOrEmpty()) null else text
        } catch (_: Exception) {
            null
        }
    }
}
