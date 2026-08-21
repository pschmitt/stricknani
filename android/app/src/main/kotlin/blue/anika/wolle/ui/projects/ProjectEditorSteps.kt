package blue.anika.wolle.ui.projects

/** Moves an editor step while keeping its attached pending images with it. */
fun <T> moveProjectEditorStep(items: List<T>, index: Int, direction: Int): List<T> {
    val target = index + direction
    if (index !in items.indices || target !in items.indices) return items
    return items.toMutableList().also { moved ->
        val item = moved.removeAt(index)
        moved.add(target, item)
    }
}
