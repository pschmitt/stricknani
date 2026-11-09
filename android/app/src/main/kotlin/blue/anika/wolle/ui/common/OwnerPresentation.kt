package blue.anika.wolle.ui.common

import androidx.compose.runtime.staticCompositionLocalOf
import blue.anika.wolle.data.db.entity.ProjectEntity
import blue.anika.wolle.data.db.entity.YarnEntity
import blue.anika.wolle.data.repository.ItemAccess

/*
 * Admin scope: an administrator also sees (and may edit) other people's projects and yarns. The
 * helpers here are the pure rules for marking those items; the composables live in OwnerBadge.kt.
 */

/** Who owns a project or yarn that is not the signed-in user's. */
data class OwnerBadgeData(val ownerId: Long, val name: String, val avatarUrl: String?)

/** What a banner on a foreign item's screen says. */
enum class OwnedKind {
    Project,
    Yarn,
}

data class OwnerBannerData(
    val ownerName: String,
    val kind: OwnedKind,
    val avatarUrl: String? = null,
)

/**
 * The badge of an item owned by someone else, or null for the user's own items, for rows without
 * owner info and while the user's own id is unknown (a legacy server): none of those are marked.
 */
fun ownerBadgeOf(
    ownerId: Long?,
    ownerName: String?,
    ownerAvatarUrl: String?,
    currentUserId: Long?,
): OwnerBadgeData? {
    if (ownerId == null || currentUserId == null || ownerId == currentUserId) return null
    return OwnerBadgeData(
        ownerId = ownerId,
        name = ownerName?.trim()?.takeIf { it.isNotEmpty() } ?: "?",
        avatarUrl = ownerAvatarUrl,
    )
}

fun ProjectEntity.ownerBadge(currentUserId: Long?): OwnerBadgeData? =
    ownerBadgeOf(ownerId, ownerName, ownerAvatarUrl, currentUserId)

fun YarnEntity.ownerBadge(currentUserId: Long?): OwnerBadgeData? =
    ownerBadgeOf(ownerId, ownerName, ownerAvatarUrl, currentUserId)

/** The banner of a foreign item's detail/edit screen; null for the user's own items. */
fun ownerBannerOf(badge: OwnerBadgeData?, kind: OwnedKind): OwnerBannerData? = badge?.let {
    OwnerBannerData(it.name, kind, it.avatarUrl)
}

/**
 * What the banner under the top bar of a project or yarn says (sharing and admin scope), most
 * specific first: a yarn read only through a shared project; an item somebody shared with the user
 * ("Shared by Anna · Can view"); an administrator's view of someone else's item; nothing for the
 * user's own items.
 */
sealed interface ItemBanner {
    data object Derived : ItemBanner

    data class SharedBy(val owner: OwnerBadgeData, val role: String) : ItemBanner

    data class Owned(val banner: OwnerBannerData) : ItemBanner
}

fun itemBannerOf(badge: OwnerBadgeData?, access: ItemAccess, kind: OwnedKind): ItemBanner? =
    when {
        access.derived -> ItemBanner.Derived
        access.isShared && badge != null -> ItemBanner.SharedBy(badge, access.role)
        badge != null -> ItemBanner.Owned(OwnerBannerData(badge.name, kind, badge.avatarUrl))
        else -> null
    }

/**
 * The yarns [ownerId] owns. A null [ownerId] means the signed-in user (a new project, or an old row
 * without owner info), and rows without owner info are the user's own too. The server only links a
 * yarn to a project of the same owner, so the project editor's picker uses the project owner's
 * yarns, and the import stash uses only the importing user's own.
 */
fun yarnsOwnedBy(
    yarns: List<YarnEntity>,
    ownerId: Long?,
    currentUserId: Long?,
): List<YarnEntity> {
    val target = ownerId ?: currentUserId
    return yarns.filter { (it.ownerId ?: currentUserId) == target }
}

/** The signed-in user and the media URL resolver, for badges deep in the composition. */
data class OwnerContext(
    val currentUserId: Long? = null,
    val resolveUrl: (String?) -> String? = { it },
)

val LocalOwnerContext = staticCompositionLocalOf { OwnerContext() }
