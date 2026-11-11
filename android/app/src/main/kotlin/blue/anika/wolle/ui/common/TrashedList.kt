package blue.anika.wolle.ui.common

import blue.anika.wolle.data.api.dto.ProjectListItemDto
import blue.anika.wolle.data.api.dto.YarnListItemDto
import blue.anika.wolle.data.repository.TrashFailure

/**
 * The Trash filter of the project and yarn lists. Trashed items are not synced into Room, so the
 * filter fetches them online from the list endpoints (`?trashed=only`) and shows loading, offline
 * and error states like the Trash screen.
 */
sealed interface TrashedList<out T> {
    /** The Trash filter is off. */
    data object Idle : TrashedList<Nothing>

    data object Loading : TrashedList<Nothing>

    data class Ready<T>(val items: List<T>) : TrashedList<T>

    data class Failed(val failure: TrashFailure) : TrashedList<Nothing>
}

/** The `owner` request value of a choice, as the website's `?owner=` writes it. */
fun OwnerChoice?.toArg(): String? =
    when (this) {
        null -> null
        OwnerChoice.Mine -> "me"
        OwnerChoice.Shared -> "shared"
        is OwnerChoice.User -> id.toString()
    }

/** What the trashed project list shows after the filters the server does not apply itself. */
fun filterTrashedProjects(
    items: List<ProjectListItemDto>,
    query: String,
    category: String?,
    tag: String?,
    favoritesOnly: Boolean,
): List<ProjectListItemDto> = items.filter { project ->
    (!favoritesOnly || project.isFavorite) &&
        (category == null || project.category == category) &&
        (tag == null || project.tags.any { it.equals(tag, ignoreCase = true) }) &&
        (query.isBlank() || project.name.contains(query, ignoreCase = true))
}

/** What the trashed yarn list shows after the search and favourites filters. */
fun filterTrashedYarns(
    items: List<YarnListItemDto>,
    query: String,
    favoritesOnly: Boolean,
): List<YarnListItemDto> = items.filter { yarn ->
    (!favoritesOnly || yarn.isFavorite) &&
        (query.isBlank() ||
            yarn.name.contains(query, ignoreCase = true) ||
            yarn.brand?.contains(query, ignoreCase = true) == true ||
            yarn.colorway?.contains(query, ignoreCase = true) == true)
}
