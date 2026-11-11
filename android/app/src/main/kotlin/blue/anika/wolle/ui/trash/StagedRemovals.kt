package blue.anika.wolle.ui.trash

/*
 * Pure rules behind the "Will be removed when you save" list of the photo editors. A removed
 * server photo only leaves the form; the form it was loaded from still holds it, so Undo can put it
 * back at its old place even after the snackbar is gone.
 */

/** The photos of [initial] whose id is in [removedIds], in their original order. */
fun <T> stagedRemovals(initial: List<T>, removedIds: Collection<Int>, idOf: (T) -> Int): List<T> =
    if (removedIds.isEmpty()) emptyList() else initial.filter { idOf(it) in removedIds }

/**
 * [current] with the photo [id] of [initial] put back: right after the nearest photo that came
 * before it in [initial] and is still in [current], or first when none is. A photo that is already
 * in [current], or unknown to [initial], leaves [current] unchanged.
 */
fun <T> restoredInOrder(current: List<T>, initial: List<T>, id: Int, idOf: (T) -> Int): List<T> {
    if (current.any { idOf(it) == id }) return current
    val index = initial.indexOfFirst { idOf(it) == id }
    if (index < 0) return current
    val currentIds = current.map(idOf)
    val anchor =
        (index - 1 downTo 0).firstNotNullOfOrNull { before ->
            currentIds.indexOf(idOf(initial[before])).takeIf { it >= 0 }
        }
    val at = if (anchor == null) 0 else anchor + 1
    return current.toMutableList().apply { add(at, initial[index]) }
}

/**
 * What the Save button of an editor says: an existing record with nothing pending shows "All
 * changes saved" and cannot be pressed; a new record can always be saved.
 */
data class SaveBarState(val allSaved: Boolean, val enabled: Boolean)

fun saveBarState(isEditing: Boolean, isDirty: Boolean, isSaving: Boolean): SaveBarState {
    val allSaved = isEditing && !isDirty && !isSaving
    return SaveBarState(allSaved = allSaved, enabled = !isSaving && !allSaved)
}
