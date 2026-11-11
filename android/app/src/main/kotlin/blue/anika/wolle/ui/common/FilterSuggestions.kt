package blue.anika.wolle.ui.common

import blue.anika.wolle.ui.palette.PaletteMatcher

/**
 * Typeahead filter suggestions of the project and yarn search fields, the same as the website's
 * (`web/src/lib/search-suggestions.ts`): typing "mü" offers the "Mütze" tag, the "Mützen" category
 * and the matching status, owner and Trash filters; picking one applies that filter. Keep both in
 * step.
 *
 * Matching ignores case and diacritics and accepts a prefix of the whole value or of one of its
 * words ("mu" and "mü" find "Mütze", "bl" finds "Baby blanket"). Owners are suggested to
 * administrators only, like the server's `GET /api/v2/search/suggestions` does for the website.
 */
enum class SuggestionGroup {
    Tags,
    Categories,
    Status,
    Owners,
    Other,
}

/** What picking a suggestion does; [Owner] carries the same choice as the owner chips. */
sealed interface SuggestionAction {
    data class Tag(val value: String) : SuggestionAction

    data class Category(val value: String) : SuggestionAction

    data class Status(val value: String) : SuggestionAction

    data class Owner(val choice: OwnerChoice) : SuggestionAction

    data object Favorite : SuggestionAction

    data object Trash : SuggestionAction
}

data class FilterSuggestion(
    /** Unique within one list, stable between keystrokes. */
    val id: String,
    val group: SuggestionGroup,
    val label: String,
    val action: SuggestionAction,
    /** The owner's avatar, for a suggested person. */
    val owner: OwnerBadgeData? = null,
)

/** The translated labels of the fixed filters, as the filter chips show them. */
data class FixedFilterLabels(
    val inProgress: String,
    val done: String,
    val mine: String,
    val shared: String,
    val favorites: String,
    val trash: String,
)

enum class SuggestionKind {
    Projects,
    Yarns,
}

/** Suggestions per group. */
const val SUGGESTION_GROUP_LIMIT = 5

private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

/** The folded text of a typed query; empty when there is nothing to look for. */
fun foldedSuggestionQuery(query: String): String = PaletteMatcher.fold(query.trim())

/**
 * 0 when the folded [query] is a prefix of the whole [value], 1 when it is a prefix of one of its
 * words, null for no match.
 */
fun suggestionMatchRank(query: String, value: String): Int? {
    if (query.isEmpty()) return null
    val text = PaletteMatcher.fold(value)
    if (text.startsWith(query)) return 0
    return if (text.split(NON_WORD).any { it.startsWith(query) }) 1 else null
}

/**
 * The suggestions for a typed text, grouped tags, categories, status, owners, other: within a group
 * whole-value prefixes first, then word prefixes, each keeping its source order. [owners] count
 * only when [isAdmin]; yarns have no tags, categories or status. Empty for a blank query.
 */
fun buildFilterSuggestions(
    query: String,
    kind: SuggestionKind,
    tags: List<String>,
    categories: List<String>,
    owners: List<OwnerBadgeData>,
    isAdmin: Boolean,
    labels: FixedFilterLabels,
): List<FilterSuggestion> {
    val folded = foldedSuggestionQuery(query)
    if (folded.isEmpty()) return emptyList()
    val projects = kind == SuggestionKind.Projects
    val candidates = buildList {
        if (projects) {
            tags.forEach {
                add(FilterSuggestion("tag:$it", SuggestionGroup.Tags, it, SuggestionAction.Tag(it)))
            }
            categories.forEach {
                add(
                    FilterSuggestion(
                        "category:$it",
                        SuggestionGroup.Categories,
                        it,
                        SuggestionAction.Category(it),
                    )
                )
            }
            add(
                FilterSuggestion(
                    "status:in_progress",
                    SuggestionGroup.Status,
                    labels.inProgress,
                    SuggestionAction.Status("in_progress"),
                )
            )
            add(
                FilterSuggestion(
                    "status:done",
                    SuggestionGroup.Status,
                    labels.done,
                    SuggestionAction.Status("done"),
                )
            )
        }
        add(
            FilterSuggestion(
                "owner:me",
                SuggestionGroup.Owners,
                labels.mine,
                SuggestionAction.Owner(OwnerChoice.Mine),
            )
        )
        add(
            FilterSuggestion(
                "owner:shared",
                SuggestionGroup.Owners,
                labels.shared,
                SuggestionAction.Owner(OwnerChoice.Shared),
            )
        )
        if (isAdmin) {
            owners.forEach {
                add(
                    FilterSuggestion(
                        "owner:${it.ownerId}",
                        SuggestionGroup.Owners,
                        it.name,
                        SuggestionAction.Owner(OwnerChoice.User(it.ownerId)),
                        owner = it,
                    )
                )
            }
        }
        add(
            FilterSuggestion(
                "other:favorite",
                SuggestionGroup.Other,
                labels.favorites,
                SuggestionAction.Favorite,
            )
        )
        add(
            FilterSuggestion(
                "other:trash",
                SuggestionGroup.Other,
                labels.trash,
                SuggestionAction.Trash,
            )
        )
    }
    val ranked =
        candidates
            .mapIndexedNotNull { order, candidate ->
                suggestionMatchRank(folded, candidate.label)?.let { Triple(candidate, it, order) }
            }
            .sortedWith(compareBy({ it.second }, { it.third }))
    return SuggestionGroup.entries.flatMap { group ->
        ranked.filter { it.first.group == group }.take(SUGGESTION_GROUP_LIMIT).map { it.first }
    }
}

/** The filters a suggestion can set, the part of a list's state that picking one changes. */
data class SuggestedFilters(
    val category: String? = null,
    val tag: String? = null,
    val favoritesOnly: Boolean = false,
    val status: String? = null,
    val owner: OwnerChoice? = null,
    val trashedOnly: Boolean = false,
)

/**
 * These filters after picking [action] in a [kind] list: that one filter is set (never toggled),
 * the rest stays. Yarn lists ignore the kinds yarns do not have.
 */
fun SuggestedFilters.applying(action: SuggestionAction, kind: SuggestionKind): SuggestedFilters =
    when (action) {
        is SuggestionAction.Tag ->
            if (kind == SuggestionKind.Projects) copy(tag = action.value) else this
        is SuggestionAction.Category ->
            if (kind == SuggestionKind.Projects) copy(category = action.value) else this
        is SuggestionAction.Status ->
            if (kind == SuggestionKind.Projects) copy(status = action.value) else this
        is SuggestionAction.Owner -> copy(owner = action.choice)
        SuggestionAction.Favorite -> copy(favoritesOnly = true)
        SuggestionAction.Trash -> copy(trashedOnly = true)
    }
