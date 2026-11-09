package blue.anika.wolle.ui.projects

/** Characters that end a tag while typing: a comma or any whitespace. */
private val TagSeparators = Regex("[\\s,]+")

/** The tag field split into finished tags and the text still being typed. */
internal data class TagSplit(val tags: List<String>, val rest: String)

/**
 * Splits the tag input at spaces and commas. With [commitAll] (the keyboard action, a paste,
 * leaving the field, saving) every piece becomes a tag; while typing, the last piece stays in the
 * field until a separator follows it.
 */
internal fun splitTagInput(text: String, commitAll: Boolean = false): TagSplit {
    val pieces = text.split(TagSeparators)
    val rest = if (commitAll) "" else pieces.last()
    val finished = if (commitAll) pieces else pieces.dropLast(1)
    return TagSplit(finished.filter { it.isNotEmpty() }, rest)
}

/** Appends the tags the list does not hold yet (case-insensitive), keeping the order. */
internal fun mergeTags(existing: List<String>, added: List<String>): List<String> {
    val merged = existing.toMutableList()
    val seen = merged.mapTo(mutableSetOf()) { it.lowercase() }
    for (tag in added) if (seen.add(tag.lowercase())) merged.add(tag)
    return if (merged.size == existing.size) existing else merged
}

/**
 * The form after the tag text changed to [text]. Typing a space or a comma turns the finished
 * pieces into chips; [commitAll] (a paste, the keyboard action, losing focus) takes the last piece
 * too.
 */
internal fun ProjectEditorFormState.withTagText(
    text: String,
    commitAll: Boolean = false,
): ProjectEditorFormState {
    val (typed, rest) = splitTagInput(text, commitAll)
    return copy(tags = if (typed.isEmpty()) tags else mergeTags(tags, typed), tagInput = rest)
}

/** The form with the tag at [index] removed. */
internal fun ProjectEditorFormState.withoutTag(index: Int): ProjectEditorFormState =
    if (index in tags.indices) copy(tags = tags.filterIndexed { i, _ -> i != index }) else this

/**
 * The form after Backspace in the empty tag field: the last chip goes. Anything else is a no-op.
 */
internal fun ProjectEditorFormState.withoutLastTagIfInputEmpty(): ProjectEditorFormState =
    if (tagInput.isEmpty() && tags.isNotEmpty()) withoutTag(tags.lastIndex) else this

/** The tags to save: the chips plus whatever is still typed in the field. */
internal fun ProjectEditorFormState.committedTags(): List<String> =
    withTagText(tagInput, commitAll = true).tags
