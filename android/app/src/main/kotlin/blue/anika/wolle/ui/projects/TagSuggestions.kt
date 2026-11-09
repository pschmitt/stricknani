package blue.anika.wolle.ui.projects

/** A tag the user already uses and the number of projects that carry it. */
data class TagCount(val tag: String, val count: Int)

/** How many suggestions the editor shows at most (the web editor shows the same number). */
internal const val MAX_TAG_SUGGESTIONS = 8

/** Most used first, then by name (case-insensitive, then exact). */
private val ByUsage: Comparator<TagCount> =
    compareByDescending<TagCount> { it.count }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.tag }
        .thenBy { it.tag }

/**
 * The distinct tags of the given projects with usage counts, like `GET /api/v2/projects/tags`
 * answers: a tag counts once per project, tags that differ only in case are one entry in their most
 * common spelling, and the result is most used first, then by name. Blank tags are ignored.
 */
internal fun tagCounts(projectTags: List<List<String>>): List<TagCount> {
    // lowercase key -> (spelling -> projects using it)
    val groups = linkedMapOf<String, MutableMap<String, Int>>()
    for (tags in projectTags) {
        val seen = mutableSetOf<String>()
        for (tag in tags.map { it.trim() }.filter { it.isNotEmpty() }) {
            val key = tag.lowercase()
            if (seen.add(key)) {
                val spellings = groups.getOrPut(key) { linkedMapOf() }
                spellings[tag] = (spellings[tag] ?: 0) + 1
            }
        }
    }
    return groups.values
        .map { spellings ->
            val best =
                spellings.entries.maxWith(
                    compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key }
                )
            TagCount(best.key, spellings.values.sum())
        }
        .sortedWith(ByUsage)
}

/**
 * The tags to suggest for what is typed, the same rules as the web editor: tags already chosen are
 * left out (case-insensitive); an empty [query] gives the most used tags; otherwise tags that start
 * with the query come first, then tags that merely contain it, each group most used first, then by
 * name.
 */
internal fun suggestTags(
    known: List<TagCount>,
    query: String,
    chosen: List<String>,
    limit: Int = MAX_TAG_SUGGESTIONS,
): List<TagCount> {
    val needle = query.trim().lowercase()
    val taken = chosen.mapTo(mutableSetOf()) { it.lowercase() }
    val open = known.filter { it.tag.lowercase() !in taken }
    if (needle.isEmpty()) return open.sortedWith(ByUsage).take(limit)
    val prefix = mutableListOf<TagCount>()
    val inside = mutableListOf<TagCount>()
    for (entry in open) {
        when (entry.tag.lowercase().indexOf(needle)) {
            0 -> prefix += entry
            in 1..Int.MAX_VALUE -> inside += entry
        }
    }
    return (prefix.sortedWith(ByUsage) + inside.sortedWith(ByUsage)).take(limit)
}

/** The form with [tag] added as a chip (when it is new) and the text field cleared. */
internal fun ProjectEditorFormState.withPickedTag(tag: String): ProjectEditorFormState =
    copy(tags = mergeTags(tags, listOf(tag)), tagInput = "")
