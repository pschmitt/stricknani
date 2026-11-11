package blue.anika.wolle.ui.common

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import blue.anika.wolle.R

/**
 * The owner filter of the project and yarn lists, the same as the web's: [Mine] is the signed-in
 * user's own items, [Shared] everybody else's (shared with the user, or an administrator's view)
 * and [User] one owner's. Null (no choice) shows everything. A `me`, `shared` or numeric user id is
 * also what the screens take as their navigation argument and what the website takes as `?owner=`.
 */
sealed interface OwnerChoice {
    data object Mine : OwnerChoice

    data object Shared : OwnerChoice

    data class User(val id: Long) : OwnerChoice
}

/** A navigation argument or URL value as a choice; anything unknown is no filter. */
fun ownerChoiceFromArg(value: String?): OwnerChoice? =
    when (val text = value?.trim()) {
        "me",
        "mine" -> OwnerChoice.Mine
        "shared" -> OwnerChoice.Shared
        else ->
            text
                ?.takeIf { it.length in 1..15 && it.all(Char::isDigit) }
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
                ?.let(OwnerChoice::User)
    }

/**
 * Whether an item owned by [ownerId] passes [choice]. Items without owner info, and every item
 * while the user's own id is unknown (a legacy server), are the user's own.
 */
fun ownerChoiceMatches(choice: OwnerChoice?, ownerId: Long?, currentUserId: Long?): Boolean {
    if (choice == null) return true
    val own = ownerId == null || currentUserId == null || ownerId == currentUserId
    return when (choice) {
        OwnerChoice.Mine -> own
        OwnerChoice.Shared -> !own
        is OwnerChoice.User -> if (own) choice.id == currentUserId else choice.id == ownerId
    }
}

/**
 * The other owners among [badges] (null = own items), once each, by name: the owner chips. Only
 * owners of items the device holds, which are the ones the user may see.
 */
fun ownerOptions(badges: Sequence<OwnerBadgeData?>): List<OwnerBadgeData> =
    badges
        .filterNotNull()
        .distinctBy { it.ownerId }
        .sortedWith(
            compareBy<OwnerBadgeData, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
                .thenBy { it.ownerId }
        )
        .toList()

/**
 * "Mine", "Shared with me", one chip per other owner and the "Trash" chip, in the order of the
 * web's pills. [trashedOnly] and [onTrashToggle] drive the Trash chip.
 */
fun LazyListScope.ownerFilterItems(
    choice: OwnerChoice?,
    owners: List<OwnerBadgeData>,
    onSelect: (OwnerChoice?) -> Unit,
    testTagPrefix: String,
    trashedOnly: Boolean = false,
    onTrashToggle: (() -> Unit)? = null,
) {
    item(key = "owner-mine") {
        FilterChip(
            selected = choice == OwnerChoice.Mine,
            onClick = { onSelect(if (choice == OwnerChoice.Mine) null else OwnerChoice.Mine) },
            label = { Text(stringResource(R.string.owner_filter_mine)) },
            modifier = Modifier.testTag("$testTagPrefix-owner-mine"),
        )
    }
    item(key = "owner-shared") {
        FilterChip(
            selected = choice == OwnerChoice.Shared,
            onClick = { onSelect(if (choice == OwnerChoice.Shared) null else OwnerChoice.Shared) },
            label = { Text(stringResource(R.string.owner_filter_shared)) },
            modifier = Modifier.testTag("$testTagPrefix-owner-shared"),
        )
    }
    items(owners.size, key = { "owner:${owners[it].ownerId}" }) { index ->
        val owner = owners[index]
        val selected = choice == OwnerChoice.User(owner.ownerId)
        FilterChip(
            selected = selected,
            onClick = { onSelect(if (selected) null else OwnerChoice.User(owner.ownerId)) },
            label = { Text(owner.name) },
            leadingIcon = { OwnerBadge(owner, size = FilterChipDefaults.IconSize) },
            modifier = Modifier.testTag("$testTagPrefix-owner-chip"),
        )
    }
    if (onTrashToggle != null) {
        item(key = "trash-only") {
            FilterChip(
                selected = trashedOnly,
                onClick = onTrashToggle,
                label = { Text(stringResource(R.string.list_filter_trash)) },
                modifier = Modifier.testTag("$testTagPrefix-trash"),
            )
        }
    }
}
