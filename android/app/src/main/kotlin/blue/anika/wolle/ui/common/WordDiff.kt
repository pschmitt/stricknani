package blue.anika.wolle.ui.common

/** A run of text in a word diff; [changed] marks words only on this side. */
data class DiffSegment(val text: String, val changed: Boolean)

data class WordDiffResult(val before: List<DiffSegment>, val after: List<DiffSegment>)

/**
 * Word-level diff of two texts (longest common subsequence over words and whitespace), ported from
 * the website's `word-diff.ts` so both platforms highlight the same words. Whitespace-only
 * differences are not highlighted. Very long texts fall back to "all changed".
 */
object WordDiff {
    /** Above this many token pairs the diff is skipped and both sides are shown whole. */
    private const val MAX_CELLS = 250_000

    private val whitespace = Regex("(\\s+)")

    private fun tokenize(text: String): List<String> = buildList {
        // Keep the separators, like JS split with a capture group.
        var last = 0
        for (match in whitespace.findAll(text)) {
            if (match.range.first > last) add(text.substring(last, match.range.first))
            add(match.value)
            last = match.range.last + 1
        }
        if (last < text.length) add(text.substring(last))
    }

    private fun MutableList<DiffSegment>.push(text: String, changed: Boolean) {
        val last = lastOrNull()
        if (last != null && last.changed == changed) {
            this[lastIndex] = DiffSegment(last.text + text, changed)
        } else {
            add(DiffSegment(text, changed))
        }
    }

    fun diff(beforeText: String, afterText: String): WordDiffResult {
        val a = tokenize(beforeText)
        val b = tokenize(afterText)
        if (a.size.toLong() * b.size > MAX_CELLS) {
            return WordDiffResult(
                before =
                    if (beforeText.isEmpty()) emptyList()
                    else listOf(DiffSegment(beforeText, true)),
                after =
                    if (afterText.isEmpty()) emptyList() else listOf(DiffSegment(afterText, true)),
            )
        }
        // lengths[i][j] = LCS length of a[i..] and b[j..]
        val lengths = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.size - 1 downTo 0) {
            for (j in b.size - 1 downTo 0) {
                lengths[i][j] =
                    if (a[i] == b[j]) lengths[i + 1][j + 1] + 1
                    else maxOf(lengths[i + 1][j], lengths[i][j + 1])
            }
        }
        val before = mutableListOf<DiffSegment>()
        val after = mutableListOf<DiffSegment>()
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            if (a[i] == b[j]) {
                before.push(a[i], false)
                after.push(b[j], false)
                i++
                j++
            } else if (lengths[i + 1][j] >= lengths[i][j + 1]) {
                before.push(a[i], a[i].isNotBlank())
                i++
            } else {
                after.push(b[j], b[j].isNotBlank())
                j++
            }
        }
        while (i < a.size) before.push(a[i], a[i].isNotBlank()).also { i++ }
        while (j < b.size) after.push(b[j], b[j].isNotBlank()).also { j++ }
        return WordDiffResult(bridgeSpaces(before), bridgeSpaces(after))
    }

    /**
     * Joins "changed, space, changed" into one highlight so a replaced phrase reads as one mark.
     */
    private fun bridgeSpaces(segments: List<DiffSegment>): List<DiffSegment> {
        val result = mutableListOf<DiffSegment>()
        segments.forEachIndexed { index, segment ->
            val bridging =
                !segment.changed &&
                    segment.text.isBlank() &&
                    segments.getOrNull(index - 1)?.changed == true &&
                    segments.getOrNull(index + 1)?.changed == true
            result.push(segment.text, segment.changed || bridging)
        }
        return result
    }
}
